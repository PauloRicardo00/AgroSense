package br.agrosense.backend.usuarios;

public class ErroUsuario extends RuntimeException {
    private final int status;
    private final String codigo;
    public ErroUsuario(int status, String codigo, String mensagem) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
    }
    public int status() { return status; }
    public String codigo() { return codigo; }
}
