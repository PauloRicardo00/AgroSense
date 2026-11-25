package br.agrosense.backend;

public record LeituraDto(
        Long id,
        String machineId,
        String messageId,
        String timestamp,
        String tipo,
        String recebidaEm,
        double airTemperature,
        double processTemperature,
        double rotationalSpeed,
        double torque,
        double toolWear,
        Double scoreFalha,
        Double limiar,
        Boolean falha,
        String versaoModelo,
        String statusAnalise,
        String origem,
        String analisadaEm) {

    public static LeituraDto de(Leitura l) {
        return new LeituraDto(
                l.getId(),
                l.getMachineId(),
                l.getMessageId(),
                l.getTimestamp() == null ? null : l.getTimestamp().toString(),
                l.getTipo(),
                l.getRecebidaEm() == null ? null : l.getRecebidaEm().toString(),
                l.getAirTemperature(),
                l.getProcessTemperature(),
                l.getRotationalSpeed(),
                l.getTorque(),
                l.getToolWear(),
                l.getScoreFalha(),
                l.getLimiar(),
                l.getFalha(),
                l.getVersaoModelo(),
                l.getStatusAnalise(),
                l.getOrigem(),
                l.getAnalisadaEm() == null ? null : l.getAnalisadaEm().toString());
    }
}
