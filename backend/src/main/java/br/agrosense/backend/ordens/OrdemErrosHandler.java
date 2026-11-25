package br.agrosense.backend.ordens;

import java.sql.SQLException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=OrdemController.class)
public class OrdemErrosHandler {
    public record Erro(String codigo,String mensagem) { }
    @ExceptionHandler(OrdemErro.class)
    public ResponseEntity<Erro> negocio(OrdemErro e) { return ResponseEntity.status(e.status).body(new Erro(e.codigo,e.getMessage())); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Erro> invalido(Exception e) { return negocio(new OrdemErro(400,"DADOS_INVALIDOS","Confira os campos e os tipos dos valores enviados")); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Erro> banco(DataAccessException e) {
        for(Throwable causa=e;causa!=null;causa=causa.getCause()) {
            if(causa instanceof SQLException sql) {
                if("P0001".equals(sql.getSQLState())) {
                    String mensagem=sql.getMessage()==null?"":sql.getMessage();
                    for(var regra:CONFLITOS.entrySet())
                        if(mensagem.contains(regra.getKey())) return negocio(new OrdemErro(409,"ORDEM_CONFLITO",regra.getValue()));
                    return negocio(new OrdemErro(409,"ORDEM_CONFLITO","A operacao nao e permitida para a situacao atual da ordem ou do cadastro"));
                }
                if("23505".equals(sql.getSQLState())) return negocio(new OrdemErro(409,"ORDEM_CONFLITO","Ja existe uma ordem ativa para esta maquina"));
            }
        }
        return negocio(new OrdemErro(503,"SERVICO_INDISPONIVEL","Nao foi possivel concluir a operacao com as ordens de servico"));
    }
    private static final Map<String,String> CONFLITOS=Map.ofEntries(
        Map.entry("Alerta encerrado","Abra a ordem enquanto o alerta estiver aberto ou reconhecido"),
        Map.entry("Maquina inativa","A maquina esta inativa"),
        Map.entry("Area inativa","O setor esta inativo"),
        Map.entry("Tecnico inativo ou nao habilitado","Escolha um tecnico ativo e habilitado para o setor"),
        Map.entry("Ordem ja finalizada","Esta ordem ja foi concluida ou cancelada"),
        Map.entry("Transicao de status invalida","Inicie uma ordem aberta antes de concluir o atendimento"),
        Map.entry("A ordem deve aguardar atribuicao ou estar aberta","So e possivel atribuir ordens aguardando atribuicao ou abertas")
    );
}
