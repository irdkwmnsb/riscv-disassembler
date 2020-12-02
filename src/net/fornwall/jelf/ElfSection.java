package net.fornwall.jelf;

public class ElfSection {
    public final ElfSectionHeader header;
    private final ElfParser parser;

    public ElfSection(ElfParser parser, ElfSectionHeader header) {
        this.header = header;
        this.parser = parser;
    }

    public byte[] rawSection() {
        parser.seek(header.section_offset);
        byte[] data = new byte[(int) header.size];
        parser.read(data);
        return data;
    }
}
