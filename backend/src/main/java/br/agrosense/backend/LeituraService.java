package br.agrosense.backend;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import br.agrosense.AgroSensePredictor;

@Service
public class LeituraService {

    private static final Logger log = LoggerFactory.getLogger(LeituraService.class);
    private final LeituraRepository repositorio;
    private final CadastroMaquina maquinas;
    private final AgroSensePredictor preditor;
    private final String versaoModelo;
    private final br.agrosense.backend.alertas.AlertaProcessador alertas;

    public LeituraService(LeituraRepository repositorio, CadastroMaquina maquinas,
                         AgroSensePredictor preditor,
                         @Value("${agrosense.modelo.versao}") String versaoModelo,
                         br.agrosense.backend.alertas.AlertaProcessador alertas) {
        if (versaoModelo == null || versaoModelo.isBlank() || versaoModelo.length() > 50) {
            throw new IllegalArgumentException("Versao do modelo ausente ou invalida");
        }
        this.repositorio = repositorio;
        this.maquinas = maquinas;
        this.preditor = preditor;
        this.versaoModelo = versaoModelo;
        this.alertas = alertas;
    }

    public void processar(String topico, String payload) {
        LeituraEntrada entrada = LeituraEntrada.ler(payload);
        Optional<Leitura> existente = repositorio.findByMessageId(entrada.messageId());
        if (existente.isPresent()) {
            conferirDuplicada(entrada, existente.get());
            return;
        }
        if (!maquinas.estaAtiva(entrada.machineId())) {
            throw new IllegalArgumentException("Maquina nao cadastrada, inativa ou com area inativa: " + entrada.machineId());
        }

        Leitura leitura;
        try {
            leitura = repositorio.saveAndFlush(entrada.novaLeitura());
        } catch (DataIntegrityViolationException e) {
            Optional<Leitura> concorrente = repositorio.findByMessageId(entrada.messageId());
            if (concorrente.isEmpty()) throw e;
            conferirDuplicada(entrada, concorrente.get());
            return;
        }

        analisar(leitura);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=10000,initialDelay=10000)
    public void retomarPendentes() {
        for (Leitura leitura : repositorio.findTop50ByStatusAnaliseOrderByRecebidaEmAsc("PENDENTE")) {
            try { analisar(leitura); }
            catch (Exception e) { log.warn("Leitura {} permanece pendente: {}",leitura.getId(),e.getClass().getSimpleName()); }
        }
    }

    private void analisar(Leitura leitura) {
        LeituraEntrada entrada = new LeituraEntrada(leitura.getMessageId(),leitura.getMachineId(),leitura.getTimestamp(),leitura.getTipo(),leitura.getAirTemperature(),leitura.getProcessTemperature(),leitura.getRotationalSpeed(),leitura.getTorque(),leitura.getToolWear());
        try {
            AgroSensePredictor.Prediction r = preditor.predict(entrada.paraModelo());
            if (!Double.isFinite(r.scoreFalha) || r.scoreFalha < 0 || r.scoreFalha > 1
                    || !Double.isFinite(r.limiar) || r.limiar < 0 || r.limiar > 1
                    || r.falha != (r.scoreFalha >= r.limiar)) {
                throw new IllegalStateException("Resultado do modelo invalido");
            }
            leitura.setScoreFalha(r.scoreFalha);
            leitura.setLimiar(r.limiar);
            leitura.setFalha(r.falha);
            leitura.setVersaoModelo(versaoModelo);
            leitura.setAnalisadaEm(Instant.now());
            leitura.setStatusAnalise("OK");
        } catch (Exception e) {
            leitura.setStatusAnalise("INDISPONIVEL");
            log.warn("Analise indisponivel para {} ({})", leitura.getMachineId(), e.getClass().getSimpleName());
        }

        alertas.finalizar(leitura);
        if ("OK".equals(leitura.getStatusAnalise())) {
            log.info("{} | score {} | limiar {} | {}", leitura.getMachineId(),
                    String.format("%.4f", leitura.getScoreFalha()), String.format("%.4f", leitura.getLimiar()),
                    Boolean.TRUE.equals(leitura.getFalha()) ? "ATENCAO (alerta_falha)" : "normal");
        }
    }

    private void conferirDuplicada(LeituraEntrada entrada, Leitura existente) {
        if (!entrada.mesmoConteudo(existente)) {
            throw new IllegalArgumentException("messageId ja utilizado com outro conteudo");
        }
        log.debug("Mensagem repetida ignorada: {}", entrada.messageId());
    }
}
