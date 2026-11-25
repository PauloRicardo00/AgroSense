package br.agrosense.backend.cadastros;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=CadastroController.class)
public class CadastroErrosHandler {
    public record Erro(String codigo,String mensagem) { }
    @ExceptionHandler(CadastroService.Erro.class)
    public ResponseEntity<Erro> negocio(CadastroService.Erro e) { return ResponseEntity.status(e.status()).body(new Erro(e.codigo(),e.getMessage())); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Erro> invalido(Exception e) { return ResponseEntity.badRequest().body(new Erro("DADOS_INVALIDOS","Confira os campos e tipos enviados")); }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Erro> duplicado(Exception e) { return ResponseEntity.status(409).body(new Erro("CADASTRO_DUPLICADO","Nome de setor ou codigo de maquina ja cadastrado")); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Erro> indisponivel(Exception e) { return ResponseEntity.status(503).body(new Erro("SERVICO_INDISPONIVEL","Nao foi possivel concluir a operacao. Tente novamente")); }
}
