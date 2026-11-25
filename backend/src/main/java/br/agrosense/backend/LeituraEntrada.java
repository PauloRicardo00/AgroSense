package br.agrosense.backend;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;

public record LeituraEntrada(String messageId, String machineId, Instant timestamp, String tipo,
                            double airTemperature, double processTemperature,
                            double rotationalSpeed, double torque, double toolWear) {

    public static final int MAX_PAYLOAD_BYTES = 16384;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public static LeituraEntrada ler(String payload) {
        if (payload == null || payload.length() > MAX_PAYLOAD_BYTES
                || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Mensagem ausente ou acima de 16 KiB");
        }
        JsonNode j;
        try {
            j = JSON.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON invalido");
        }
        if (j == null || !j.isObject()) throw new IllegalArgumentException("A mensagem deve ser um objeto JSON");
        String messageId = texto(j, "messageId", 100);
        String machineId = texto(j, "machineId", 30);
        String tipo = texto(j, "type", 1);
        if (!Set.of("L", "M", "H").contains(tipo)) throw new IllegalArgumentException("type deve ser L, M ou H");
        Instant timestamp;
        try {
            timestamp = Instant.parse(texto(j, "timestamp", 40)).truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("timestamp obrigatorio: use ISO-8601 com fuso, como 2026-11-10T12:00:00Z");
        }
        return new LeituraEntrada(messageId, machineId, timestamp, tipo,
                numero(j, "airTemperature", false), numero(j, "processTemperature", false),
                numero(j, "rotationalSpeed", false), numero(j, "torque", false), numero(j, "toolWear", true));
    }

    private static String texto(JsonNode j, String campo, int maximo) {
        JsonNode n = j.get(campo);
        if (n == null || !n.isTextual() || n.textValue().isBlank() || n.textValue().length() > maximo
                || n.textValue().chars().anyMatch(Character::isISOControl)
                || !n.textValue().equals(n.textValue().strip())) {
            throw new IllegalArgumentException("Campo de texto ausente ou invalido: " + campo);
        }
        return n.textValue();
    }

    private static double numero(JsonNode j, String campo, boolean permiteZero) {
        JsonNode n = j.get(campo);
        if (n == null || !n.isNumber()) throw new IllegalArgumentException("Campo numerico obrigatorio: " + campo);
        double valor = n.doubleValue();
        if (!Double.isFinite(valor) || valor < 0 || (!permiteZero && valor == 0)) {
            throw new IllegalArgumentException("Valor numerico invalido: " + campo);
        }
        return valor;
    }

    public Leitura novaLeitura() {
        Leitura l = new Leitura();
        l.setMessageId(messageId);
        l.setMachineId(machineId);
        l.setTimestamp(timestamp);
        l.setTipo(tipo);
        l.setAirTemperature(airTemperature);
        l.setProcessTemperature(processTemperature);
        l.setRotationalSpeed(rotationalSpeed);
        l.setTorque(torque);
        l.setToolWear(toolWear);
        l.setOrigem("simulado");
        l.setRecebidaEm(Instant.now());
        l.setStatusAnalise("PENDENTE");
        return l;
    }

    public JsonNode paraModelo() {
        return JSON.createObjectNode().put("type", tipo).put("airTemperature", airTemperature)
                .put("processTemperature", processTemperature).put("rotationalSpeed", rotationalSpeed)
                .put("torque", torque).put("toolWear", toolWear);
    }

    public boolean mesmoConteudo(Leitura l) {
        return machineId.equals(l.getMachineId()) && timestamp.equals(l.getTimestamp()) && tipo.equals(l.getTipo())
                && airTemperature == l.getAirTemperature() && processTemperature == l.getProcessTemperature()
                && rotationalSpeed == l.getRotationalSpeed() && torque == l.getTorque() && toolWear == l.getToolWear();
    }
}
