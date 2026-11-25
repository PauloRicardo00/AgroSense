package br.agrosense.backend;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agrosense.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttListener {

    private static final Logger log = LoggerFactory.getLogger(MqttListener.class);

    private final LeituraService servico;

    @Value("${agrosense.mqtt.broker}")
    private String broker;

    @Value("${agrosense.mqtt.topic}")
    private String topic;

    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agrosense-mqtt").factory());
    private final MqttConnectOptions opcoes = new MqttConnectOptions();
    private MqttClient client;
    private boolean inscrito;
    private boolean encerrando;
    private boolean falhaRegistrada;

    public MqttListener(LeituraService servico) {
        this.servico = servico;
    }

    @PostConstruct
    public void iniciar() throws MqttException {
        client = new MqttClient(broker, "agrosense-backend", new MemoryPersistence());

        opcoes.setCleanSession(true);
        opcoes.setConnectionTimeout(5);
        opcoes.setKeepAliveInterval(15);
        client.setTimeToWait(10000);
        monitor.scheduleWithFixedDelay(this::garantirAssinatura, 0, 5, TimeUnit.SECONDS);
    }

    private synchronized void garantirAssinatura() {
        if (encerrando) return;
        try {
            if (!client.isConnected()) {
                if (inscrito) log.warn("Conexao MQTT perdida. Reconectando em {}", broker);
                inscrito = false;
                client.connect(opcoes);
            }
            // Uma sessão limpa precisa de nova assinatura após cada conexão.
            if (!inscrito) {
                client.subscribe(topic, 0, this::processar);
                inscrito = true;
                falhaRegistrada = false;
                log.info("Conectado em {} e inscrito no topico {}", broker, topic);
            }
        } catch (MqttException e) {
            if (!falhaRegistrada) {
                log.warn("Conexao ou assinatura MQTT indisponivel em {} (codigo {}). Nova tentativa em 5 s.",
                        broker, e.getReasonCode());
                falhaRegistrada = true;
            }
        }
    }

    private void processar(String topicoRecebido, MqttMessage mensagem) {
        try {
            if (mensagem.getPayload().length > LeituraEntrada.MAX_PAYLOAD_BYTES) {
                log.warn("Mensagem MQTT rejeitada: tamanho acima do limite de 16 KiB");
                return;
            }
            String payload = new String(mensagem.getPayload(), StandardCharsets.UTF_8);
            servico.processar(topicoRecebido, payload);
        } catch (Exception e) {

            log.warn("Mensagem ignorada em {}: {}", topicoRecebido, e.getMessage());
        }
    }

    @PreDestroy
    public synchronized void encerrar() throws MqttException {
        encerrando = true;
        monitor.shutdown();
        if (client != null) {
            try {
                if (client.isConnected()) client.disconnect();
            } finally {
                client.close();
            }
        }
    }
}
