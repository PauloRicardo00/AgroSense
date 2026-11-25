package br.agrosense.backend.seguranca;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

public class TentativasLogin {
    private final Clock clock;
    private final Map<String, Janela> registros = new HashMap<>();
    public TentativasLogin() { this(Clock.systemUTC()); }
    TentativasLogin(Clock clock) { this.clock = clock; }

    public synchronized boolean permitir(String ip, String email) {
        long agora = clock.millis();
        registros.entrySet().removeIf(e -> agora - e.getValue().inicio >= 60000);
        String conta = "email:" + UsuariosAcesso.normalizarEmail(email);
        String endereco = "ip:" + ip;
        if (registros.size() > 9997) return false;
        Janela porIp = registros.computeIfAbsent(endereco, k -> new Janela(agora));
        Janela porConta = registros.computeIfAbsent(conta, k -> new Janela(agora));
        if (porIp.total >= 20 || porConta.total >= 5) return false;
        porIp.total++;
        porConta.total++;
        return true;
    }

    public synchronized void sucesso(String email) {
        registros.remove("email:" + UsuariosAcesso.normalizarEmail(email));
    }

    private static final class Janela {
        final long inicio;
        int total;
        Janela(long inicio) { this.inicio = inicio; }
    }
}
