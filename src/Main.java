import me.alzhanov.ELF.RISCVDisassembler;
import net.fornwall.jelf.ElfFile;

import java.io.*;

public class Main {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: <input file> [<output file>]");
            return;
        }
        try {
            OutputStreamWriter output = null;
            try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(args[0]))) {
                if (args.length > 1) {
                    output = new OutputStreamWriter(new FileOutputStream(args[1]));
                } else {
                    output = new OutputStreamWriter(System.out);
                }
                RISCVDisassembler disassembler = new RISCVDisassembler(ElfFile.from(stream));
                disassembler.dumpAll(output);
            } finally {
                if (output != null) {
                    output.close();
                }
            }
        } catch (FileNotFoundException e) {
            System.err.println("File is not found.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
