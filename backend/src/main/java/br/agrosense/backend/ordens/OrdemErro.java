package br.agrosense.backend.ordens;

public class OrdemErro extends RuntimeException {
    public final int status;
    public final String codigo;
    public OrdemErro(int status, String codigo, String mensagem) {
        super(mensagem);
        this.status=status;
        this.codigo=codigo;
    }
}
