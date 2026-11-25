package br.agrosense.backend;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "leitura")
public class Leitura {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "machine_id", nullable = false, length = 30)
    private String machineId;
    @Column(name = "message_id", length = 100, unique = true)
    private String messageId;
    @Column(name = "medida_em")
    private Instant timestamp;
    @Column(name = "tipo", nullable = false, length = 1)
    private String tipo;
    @Column(name = "recebida_em", nullable = false)
    private Instant recebidaEm;

    @Column(name = "air_temperature", nullable = false)
    private double airTemperature;
    @Column(name = "process_temperature", nullable = false)
    private double processTemperature;
    @Column(name = "rotational_speed", nullable = false)
    private double rotationalSpeed;
    @Column(name = "torque", nullable = false)
    private double torque;
    @Column(name = "tool_wear", nullable = false)
    private double toolWear;

    @Column(name = "score_falha")
    private Double scoreFalha;
    @Column(name = "limiar")
    private Double limiar;
    @Column(name = "falha")
    private Boolean falha;
    @Column(name = "versao_modelo", length = 50)
    private String versaoModelo;
    @Column(name = "status_analise", nullable = false, length = 20)
    private String statusAnalise;

    @Column(name = "origem", nullable = false, length = 20)
    private String origem = "simulado";

    @Column(name = "analisada_em")
    private Instant analisadaEm;

    public String getOrigem() { return origem; }
    public void setOrigem(String origem) { this.origem = origem; }
    public Instant getAnalisadaEm() { return analisadaEm; }
    public void setAnalisadaEm(Instant analisadaEm) { this.analisadaEm = analisadaEm; }

    public Long getId() { return id; }

    public String getMachineId() { return machineId; }
    public void setMachineId(String machineId) { this.machineId = machineId; }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public Instant getRecebidaEm() { return recebidaEm; }
    public void setRecebidaEm(Instant recebidaEm) { this.recebidaEm = recebidaEm; }

    public double getAirTemperature() { return airTemperature; }
    public void setAirTemperature(double airTemperature) { this.airTemperature = airTemperature; }

    public double getProcessTemperature() { return processTemperature; }
    public void setProcessTemperature(double processTemperature) { this.processTemperature = processTemperature; }

    public double getRotationalSpeed() { return rotationalSpeed; }
    public void setRotationalSpeed(double rotationalSpeed) { this.rotationalSpeed = rotationalSpeed; }

    public double getTorque() { return torque; }
    public void setTorque(double torque) { this.torque = torque; }

    public double getToolWear() { return toolWear; }
    public void setToolWear(double toolWear) { this.toolWear = toolWear; }

    public Double getScoreFalha() { return scoreFalha; }
    public void setScoreFalha(Double scoreFalha) { this.scoreFalha = scoreFalha; }

    public Double getLimiar() { return limiar; }
    public void setLimiar(Double limiar) { this.limiar = limiar; }

    public Boolean getFalha() { return falha; }
    public void setFalha(Boolean falha) { this.falha = falha; }

    public String getVersaoModelo() { return versaoModelo; }
    public void setVersaoModelo(String versaoModelo) { this.versaoModelo = versaoModelo; }

    public String getStatusAnalise() { return statusAnalise; }
    public void setStatusAnalise(String statusAnalise) { this.statusAnalise = statusAnalise; }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
