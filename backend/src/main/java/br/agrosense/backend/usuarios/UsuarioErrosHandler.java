package br.agrosense.backend.usuarios;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = UsuarioController.class)
public class UsuarioErrosHandler {
    @ExceptionHandler(ErroUsuario.class)
    public ResponseEntity<Erro> negocio(ErroUsuario e) { return ResponseEntity.status(e.status()).body(new Erro(e.codigo(), e.getMessage())); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Erro> invalido(Exception e) { return ResponseEntity.badRequest().body(new Erro("DADOS_INVALIDOS", "Confira os campos e tipos enviados")); }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Erro> duplicado(DuplicateKeyException e) { return ResponseEntity.status(409).body(new Erro("EMAIL_EM_USO", "Este e-mail ja esta cadastrado")); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Erro> banco(DataAccessException e) { return ResponseEntity.status(503).body(new Erro("SERVICO_INDISPONIVEL", "Nao foi possivel concluir a operacao. Tente novamente")); }
    public record Erro(String codigo, String mensagem) { }
}
