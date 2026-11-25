package br.agrosense.backend;

import java.nio.file.Path;

import br.agrosense.AgroSensePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModeloConfig {

    private static final Logger log = LoggerFactory.getLogger(ModeloConfig.class);

    @Bean
    public AgroSensePredictor agroSensePredictor(@Value("${agrosense.modelo.pasta}") String pasta)
            throws Exception {
        Path caminho = Path.of(pasta);
        AgroSensePredictor preditor = new AgroSensePredictor(caminho);
        log.info("Modelo carregado de {} | limiar de atencao: {}",
                caminho.toAbsolutePath(), preditor.getThreshold());
        return preditor;
    }
}
