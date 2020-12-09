package me.alzhanov.ELF;

import net.fornwall.jelf.*;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.InputMismatchException;
import java.util.Set;

public class RISCVDisassembler {
    final ElfFile file;

    public RISCVDisassembler(ElfFile file) {
        if (file.objectSize != ElfFile.CLASS_32) {
            throw new InputMismatchException("That elf is not 32 bit.");
        }
        if (file.arch != 0xF3) {
            throw new InputMismatchException("That elf is not for RISC-V.");
        }
        this.file = file;
    }

    public void dumpAll(OutputStreamWriter output) {
        PrintWriter writer = new PrintWriter(output);
        doDisassemble(writer);
        dumpSymTable(writer);
        writer.flush();
    }

    String getRegisterString(int reg) {
        if (reg == 0)
            return "zero";
        else if (reg == 1)
            return "ra";
        else if (reg == 2)
            return "sp";
        else if (reg == 3)
            return "gp";
        else if (reg == 4)
            return "tp";
        else if (5 <= reg && reg <= 7)
            return "t" + (reg - 5);
        else if (reg == 8)
            return "s0";
        else if (reg == 9)
            return "s1";
        else if (10 <= reg && reg <= 17)
            return "a" + (reg - 10);
        else if (18 <= reg && reg <= 27)
            return "s" + (reg - 18 + 2);
        else if (28 <= reg && reg <= 31)
            return "t" + (reg - 28 + 3);
        else
            throw new AssertionError("RISC-V doesn't have register " + reg);
    }

    private String getSymbolForAddr(long loc, boolean isUnmarked) {
        ElfSymbol symb = file.getELFSymbol(loc);
        String locS = String.format("0x%08X", loc);
        if (symb != null && symb.st_value == loc && symb.section_type == ElfSymbol.STT_FUNC) {
            locS += " <" + symb.getName() + ">";
        } else if (isUnmarked) {
            locS += String.format(" <LOC_0x%08X>", loc);
        }
        return locS;
    }

    Set<Long> findUnmarkedLocations(ElfSection textSection) {
        long curOffset = 0;
        file.parser.seek(textSection.header.section_offset);
        Set<Long> symbs = new HashSet<>();
        while (curOffset < textSection.header.size) {
            long virtualAddress = curOffset + textSection.header.address;
            int instruction = file.parser.readInt();
            int opcode = instruction & ((1 << 7) - 1);
            if (opcode == 0b1101111) { // JAL
                int offset = getOffsetForJType(instruction);
                ElfSymbol symb = file.getELFSymbol(virtualAddress + offset);
                if (symb == null || symb.st_value != virtualAddress + offset || symb.section_type == ElfSymbol.STT_FUNC)
                    symbs.add(virtualAddress + offset);
            } else if (opcode == 0b1100011) { // B-type
                int offset = getOffsetForBType(instruction);
                ElfSymbol symb = file.getELFSymbol(virtualAddress + offset);
                if (symb == null || symb.st_value != virtualAddress + offset || symb.section_type == ElfSymbol.STT_FUNC)
                    symbs.add(virtualAddress + offset);
            }
            curOffset += 4;
        }
        return symbs;
    }

    private int getOffsetForBType(int instruction) {
        // fucking hell...
        // 12 10 9 8 7 6 5 . . . . . . . . . . . . . 4 3 2 1 11 . . . . . . .

        // 12 11 10 9 8 7 6 5 4 3 2 1 0
        int offset = (((instruction >>> 8) & ((1 << 4) - 1)) << 1) |
                (((instruction >>> 25) & ((1 << 6) - 1)) << 5) |
                (((instruction >>> 7) & 1) << 11) |
                (((instruction >>> 31) & 1) << 12); // probably has a bug - did not test
        offset = signExtend(offset, 12);
        return offset;
    }

    private int getOffsetForJType(int instruction) {
        // 20 | 10:1 | 11 | 19:12 <- боже мой
        // 20 10  9  8  7  6  5  4  3  2  1 11 19 18 17 16 15 14 13 12
        // 20 19 18 17 16 15 14 13 12 11 10  9  8  7  6  5  4  3  2  1  0

        // 20 10  9  8  7  6  5  4  3  2  1 11 19 18 17 16 15 14 13 12
        //  1  0  0  1  0  1  1  0  0  1  0  0  1  0  0  1  0  0  0  1

        // 20 19 18 17 16 15 14 13 12 11 10  9  8  7  6  5  4  3  2  1  0
        //  1  1  0  0  1  0  0  0  1  0  0  0  1  0  1  1  0  0  1  0  0
        int imm = instruction >> 12;
        int offset = (((imm >>> 9) & ((1 << 10) - 1)) << 1) |
                (((imm >>> 8) & 1) << 11) |
                ((imm & ((1 << 8) - 1)) << 12) |
                (((imm >>> 19) & 1) << 20);
        offset = signExtend(offset, 20);
        return offset;
    }

    public void doDisassemble(PrintWriter out) {
        file.getDynamicSymbolTableSection();
        file.getSymbolTableSection();
        ElfSection textSection = file.firstSectionByName(".text");
        if (textSection == null)
            throw new InputMismatchException("No .text found");
        long curOffset = 0;
        Set<Long> unmarked = findUnmarkedLocations(textSection);
        file.parser.seek(textSection.header.section_offset);
        while (curOffset < textSection.header.size) {
            long virtualAddress = curOffset + textSection.header.address;
            out.print(String.format("%08X: ", virtualAddress));
            int instruction = file.parser.readInt();
            ElfSymbol symb = file.getELFSymbol(virtualAddress);
            if (symb != null && symb.st_value == virtualAddress && symb.section_type == ElfSymbol.STT_FUNC) {
                out.printf("<%s>\t", symb.getName());
            } else if (unmarked.contains(virtualAddress)) {
                out.printf("<LOC_0x%08X>\t", virtualAddress);
            } else {
                out.print("\t");
            }
            int opcode = instruction & ((1 << 7) - 1);
            int rd = instruction >> 7 & ((1 << 5) - 1);
            int funct3 = instruction >> 12 & ((1 << 3) - 1);
            int rs1 = instruction >> 15 & ((1 << 5) - 1);
            int rs2 = instruction >> 20 & ((1 << 5) - 1);
            int imm110 = instruction >> 20 & ((1 << 12) - 1);
            int funct7 = instruction >> 25;
            if (opcode == 0b0110111) { // LUI
                out.printf("%6s %s, %s%n", "lui", getRegisterString(rd), Integer.toUnsignedString((instruction >>> 12) << 12));
            } else if (opcode == 0b0010111) { // AUIPC
                out.printf("%6s %s, %s%n", "auipc", getRegisterString(rd), Integer.toUnsignedString((instruction >>> 12) << 12));
            } else if (opcode == 0b1101111) { // JAL
                int offset = getOffsetForJType(instruction);
                long jumpTo = virtualAddress + offset;
                out.printf("%6s %s, %d\t#%s%n", "jal", getRegisterString(rd), offset, getSymbolForAddr(jumpTo, unmarked.contains(jumpTo)));
            } else if (opcode == 0b1100111 && funct3 == 0b000) { // jalr
                imm110 = signExtend(imm110, 11);
                out.printf("%6s %s, %s, %d%n", "jalr", getRegisterString(rd), getRegisterString(rs1), imm110);
            } else if (opcode == 0b1100011) { // B-type
                int offset = getOffsetForBType(instruction);
                String instr = new String[]{"beq", "bne", "??", "??", "blt", "bge", "bltu", "bgeu"}[funct3];
                long jumpTo = virtualAddress + offset;
                out.printf("%6s %s, %s, %d\t#%s %n", instr, getRegisterString(rs1), getRegisterString(rs2), offset, getSymbolForAddr(jumpTo, unmarked.contains(jumpTo)));
            } else if (opcode == 0b0000011) { // I-type - LB, LH, LW, LBU, LHU
                String instr = new String[]{"lb", "lh", "lw", "??", "lbu", "lhu", "??", "??"}[funct3];
                out.printf("%6s %s, %d(%s)%n", instr, getRegisterString(rd), signExtend(imm110, 11) , getRegisterString(rs1));
            } else if (opcode == 0b0100011) { // S-type SB, SH, SW
                String instr = new String[]{"sb", "sh", "sw", "??", "??", "??", "??", "??"}[funct3];
                int imm = rd | ((imm110 >>> 5) << 5);
                out.printf("%6s %s, %d(%s)%n", instr, getRegisterString(rs2), signExtend(imm, 11), getRegisterString(rs1));
            } else if (opcode == 0b0010011) {
                if (funct3 == 0b001) { // SLLI
                    out.printf("%6s %s, %s, %d%n", "slli", getRegisterString(rd), getRegisterString(rs1), imm110);
                } else if (funct3 == 0b101) {
                    if (funct7 == 0b0100000) {// SRAI
                        out.printf("%6s %s, %s, %d%n", "srai", getRegisterString(rd), getRegisterString(rs1), imm110 & ((1 << 5) - 1));
                    } else { // SRLI
                        out.printf("%6s %s, %s, %d%n", "srli", getRegisterString(rd), getRegisterString(rs1), imm110);
                    }
                } else { // I-type - ADDI, SLTI, SLTIU, XORI, ORI, ANDI
                    String instr = new String[]{"addi", "??", "slti", "sltiu", "xori", "??", "ori", "andi"}[funct3];
                    if (instr.equals("addi") || instr.equals("slti")) { // sign-extend
                        imm110 = signExtend(imm110, 11);
                    }
                    out.printf("%6s %s, %s, %d%n", instr, getRegisterString(rd), getRegisterString(rs1), imm110);
                }
            } else if (opcode == 0b110011) { // R-type
                if (funct7 == 0b0100000) {// SUB, SRA
                    String instr = new String[]{"sub", "??", "??", "??", "??", "sra", "??", "??"}[funct3];
                    out.printf("%6s %s, %s, %s%n", instr, getRegisterString(rd), getRegisterString(rs1), getRegisterString(rs2));
                } else if (funct7 == 0) {
                    String instr = new String[]{"add", "sll", "slt", "sltu", "xor", "srl", "or", "and"}[funct3];
                    out.printf("%6s %s, %s, %s%n", instr, getRegisterString(rd), getRegisterString(rs1), getRegisterString(rs2));
                } else if (funct7 == 1) {
                    String instr = new String[]{"mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu"}[funct3];
                    out.printf("%6s %s, %s, %s%n", instr, getRegisterString(rd), getRegisterString(rs1), getRegisterString(rs2));
                }
            } else if (opcode == 0b0001111) {
                if (funct3 == 1) { // FENCE.I
                    out.printf("%6s%n", "fence.i");
                } else { // FENCE
                    out.printf("%6s %d, %d%n", "fence", imm110 >>> 4 << 4, imm110 & ((1 << 4) - 1));
                }
            } else if (opcode == 0b1110011) {
                if (funct3 == 0) {
                    if (imm110 == 0) { // ECALL
                        out.printf("%6s%n", "ecall");
                    } else if (imm110 == 1) { // EBREAK
                        out.printf("%6s%n", "ebreak");
                    } else {
                        out.printf("????%n");
                    }
                } else {
                    String instr = new String[]{"", "csrrw", "csrrs", "csrrc", "??", "csrrwi", "csrrsi", "csrrci"}[funct3];
                    out.printf("%6s %s, %s, %s%n", instr, getRegisterString(rd), imm110, getRegisterString(rs1));
                }
            } else {
                out.printf("????%n");
            }
            curOffset += 4;
        }
    }

    private int signExtend(int val, int nBits) {
        if ((val & (1 << nBits)) != 0) { // I hope it works cuz i don't have binaries to test this
            val = -(-val & ((1 << nBits) - 1));
        }
        return val;
    }

    private static int getIntWidth(int a) {
        if (a == 0)
            return 1;
        return (int) Math.floor(Math.log10(Math.abs(a))) + 1 + (a < 0 ? 1 : 0);
    }

    static String symbolTypeToString(int type) {
        switch (type) {
            case (ElfSymbol.STT_NOTYPE):
                return "NOTYPE";
            case (ElfSymbol.STT_OBJECT):
                return "OBJECT";
            case (ElfSymbol.STT_FUNC):
                return "FUNC";
            case (ElfSymbol.STT_SECTION):
                return "SECTION";
            case (ElfSymbol.STT_FILE):
                return "FILE";
            case (ElfSymbol.STT_LOPROC):
                return "LOPROC";
            case (ElfSymbol.STT_HIPROC):
                return "HIPROC";
            default:
                return "UNKNOWN";
        }
    }

    static String bindingToString(int binding) {
        switch (binding) {
            case (ElfSymbol.BINDING_GLOBAL):
                return "GLOBAL";
            case (ElfSymbol.BINDING_HIPROC):
                return "HIPROC";
            case (ElfSymbol.BINDING_LOCAL):
                return "LOCAL";
            case (ElfSymbol.BINDING_LOPROC):
                return "LOPROC";
            case (ElfSymbol.BINDING_WEAK):
                return "WEAK";
            default:
                return "UNKNOWN";
        }
    }

    static String visibilityToString(ElfSymbol.Visibility visibility) {
        switch (visibility) {
            case STV_HIDDEN:
                return "HIDDEN";
            case STV_DEFAULT:
                return "DEFAULT";
            case STV_INTERNAL:
                return "INTERNAL";
            case STV_PROTECTED:
                return "PROTECTED";
            default:
                return "UNKNOWN";
        }
    }

    public void dumpSymTable(PrintWriter out) {
        out.println("Symtable:");
        ElfSymbolTableSection symtable = file.getSymbolTableSection();
        int symbolCount = symtable.symbols.length;
        int firstColWidth = getIntWidth(symbolCount);
        out.println(String.format("%" + (firstColWidth + 2) + "s   %8s %5s %7s %7s %8s %4s %s",
                "Symbol".substring(0, firstColWidth + 2), "Value", "Size", "Type", "Bind", "Vis", "Index", "Name"));
        for (int i = 0; i < symbolCount; i++) {
            ElfSymbol symbol = symtable.symbols[i];
            out.println(String.format("[%" + firstColWidth + "s] 0x%08X %5s %7s %7s %8s %4s %s",
                    i,
                    symbol.st_value,
                    symbol.st_size,
                    symbolTypeToString(symbol.getType()),
                    bindingToString(symbol.getBinding()),
                    visibilityToString(symbol.getVisibility()),
                    shindexToString(symbol.st_shndx),
                    symbol.st_name == 0 ? "" : symbol.getName()
            ));
        }
    }

    private String shindexToString(short stShndx) {
        if (stShndx == ElfSectionHeader.SHN_ABS) {
            return "ABS";
        } else if (stShndx == ElfSectionHeader.SHN_COMMON) {
            return "COMMON";
        } else if (Short.compareUnsigned(ElfSectionHeader.SHN_LOPROC, stShndx) <= 0 && Short.compareUnsigned(stShndx, ElfSectionHeader.SHN_HIPROC) <= 0) {
            return "PROC_RES";
        } else if (Short.compareUnsigned(ElfSectionHeader.SHN_LOOS, stShndx) <= 0 && Short.compareUnsigned(stShndx, ElfSectionHeader.SHN_HIOS) <= 0) {
            return "OS_RES";
        } else if (stShndx == ElfSectionHeader.SHN_UNDEF) {
            return "UNDEF";
        } else if (stShndx == ElfSectionHeader.SHN_XINDEX) {
            return "XINDEX";
        } else if (ElfSectionHeader.SHN_LORESERVE <= stShndx && stShndx <= ElfSectionHeader.SHN_HIRESERVE) {
            return "RESERVED";
        } else {
            return String.valueOf(stShndx);
        }
    }
}
