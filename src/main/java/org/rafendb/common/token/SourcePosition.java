package org.rafendb.common.token;

public class SourcePosition {
    public int offset;
    public int line;
    public int column;
    public static final SourcePosition START = new SourcePosition(0, 1, 1);

    public SourcePosition(int offset, int line, int column) {
        this.offset = offset;
        this.line = line;
        this.column = column;
    }
    public int getOffset(){ return offset; }
    public int getLine(){ return line; }
    public int getColumn(){ return column; }
    @Override 
    public String toString(){
        return "line " + line + ", column " + column;
    }
}
