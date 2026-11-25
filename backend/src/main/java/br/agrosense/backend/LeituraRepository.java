package br.agrosense.backend;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeituraRepository extends JpaRepository<Leitura, Long> {

    Optional<Leitura> findByMessageId(String messageId);
    List<Leitura> findTop50ByStatusAnaliseOrderByRecebidaEmAsc(String statusAnalise);

    List<Leitura> findTop50ByOrderByRecebidaEmDesc();

    Optional<Leitura> findFirstByMachineIdOrderByRecebidaEmDesc(String machineId);
}
