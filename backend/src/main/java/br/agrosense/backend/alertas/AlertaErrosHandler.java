package br.agrosense.backend.alertas;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=AlertaController.class)
public class AlertaErrosHandler {
    public record Erro(String codigo,String mensagem) { }
    @ExceptionHandler(AlertaConsulta.Erro.class)
    public ResponseEntity<Erro> negocio(AlertaConsulta.Erro e) { return ResponseEntity.status(e.status).body(new Erro(e.codigo,e.getMessage())); }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Erro> invalido(Exception e) { return ResponseEntity.badRequest().body(new Erro("DADOS_INVALIDOS","Confira os parametros")); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Erro> banco(Exception e) { return ResponseEntity.status(503).body(new Erro("SERVICO_INDISPONIVEL","Nao foi possivel concluir a operacao com os alertas")); }
}
