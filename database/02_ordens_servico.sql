CREATE FUNCTION atribuir_ordem_servico(
    p_os_id BIGINT,
    p_usuario_id BIGINT,
    p_modo TEXT,
    p_tecnico_id BIGINT DEFAULT NULL
) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_os ordem_servico%ROWTYPE;
    v_tecnico BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(80321, 1);
    IF NOT EXISTS (SELECT 1 FROM usuario WHERE id = p_usuario_id AND ativo AND perfil IN ('GESTOR', 'ADMIN')) THEN
        RAISE EXCEPTION 'Atribuicao exige gestor ou administrador ativo';
    END IF;
    IF p_modo IS NULL OR p_modo NOT IN ('MANUAL', 'AUTOMATICA') THEN
        RAISE EXCEPTION 'Modo de atribuicao invalido';
    END IF;
    IF p_modo = 'AUTOMATICA' AND p_tecnico_id IS NOT NULL THEN
        RAISE EXCEPTION 'A atribuicao automatica escolhe o tecnico';
    END IF;
    SELECT * INTO v_os FROM ordem_servico WHERE id = p_os_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Ordem de servico inexistente'; END IF;
    IF v_os.status NOT IN ('AGUARDANDO_ATRIBUICAO', 'ABERTA') THEN
        RAISE EXCEPTION 'A ordem deve aguardar atribuicao ou estar aberta';
    END IF;

    IF p_modo = 'AUTOMATICA' THEN
        SELECT u.id INTO v_tecnico
        FROM usuario u
        JOIN tecnico_setor ts ON ts.tecnico_id = u.id
        JOIN setor s ON s.id = ts.setor_id
        WHERE u.ativo AND u.perfil = 'TECNICO' AND ts.habilitado AND s.ativo
          AND ts.setor_id = v_os.setor_id
        ORDER BY (
            SELECT count(*) FROM ordem_servico o
            WHERE o.tecnico_id = u.id AND o.status IN ('ABERTA', 'EM_ANDAMENTO') AND o.id <> p_os_id
        ), u.id
        LIMIT 1
        FOR SHARE OF u, ts, s;
    ELSE
        v_tecnico := p_tecnico_id;
    END IF;

    IF v_tecnico IS NOT NULL THEN
        PERFORM u.id FROM usuario u
        JOIN tecnico_setor ts ON ts.tecnico_id = u.id
        JOIN setor s ON s.id = ts.setor_id
        WHERE u.id = v_tecnico AND u.ativo AND u.perfil = 'TECNICO'
          AND ts.setor_id = v_os.setor_id AND ts.habilitado AND s.ativo
        FOR SHARE OF u, ts, s;
        IF NOT FOUND THEN RAISE EXCEPTION 'Tecnico inativo ou nao habilitado para a area'; END IF;
    END IF;

    UPDATE ordem_servico SET
        tecnico_id = v_tecnico,
        modo_atribuicao = p_modo,
        status = CASE WHEN v_tecnico IS NULL THEN 'AGUARDANDO_ATRIBUICAO' ELSE 'ABERTA' END,
        atribuido_por = CASE WHEN v_tecnico IS NULL THEN NULL ELSE p_usuario_id END,
        atribuida_em = CASE WHEN v_tecnico IS NULL THEN NULL ELSE clock_timestamp() END,
        atualizada_em = clock_timestamp()
    WHERE id = p_os_id;
    INSERT INTO os_historico(os_id, usuario_id, evento, dados)
    VALUES (p_os_id, p_usuario_id, 'ATRIBUICAO', jsonb_build_object(
        'modo', p_modo, 'tecnico_anterior', v_os.tecnico_id, 'tecnico_atual', v_tecnico));
    RETURN v_tecnico;
END;
$$;

CREATE FUNCTION criar_ordem_servico(
    p_alerta_id BIGINT,
    p_usuario_id BIGINT,
    p_modo TEXT DEFAULT 'MANUAL',
    p_tecnico_id BIGINT DEFAULT NULL,
    p_prioridade TEXT DEFAULT 'MEDIA',
    p_prazo TIMESTAMPTZ DEFAULT NULL,
    p_descricao TEXT DEFAULT NULL
) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_alerta alerta%ROWTYPE;
    v_maquina maquina%ROWTYPE;
    v_leitura leitura%ROWTYPE;
    v_id BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(80321, 1);
    IF NOT EXISTS (SELECT 1 FROM usuario WHERE id = p_usuario_id AND ativo AND perfil IN ('GESTOR', 'ADMIN')) THEN
        RAISE EXCEPTION 'Criacao de OS exige gestor ou administrador ativo';
    END IF;
    SELECT * INTO v_alerta FROM alerta WHERE id = p_alerta_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Alerta inexistente'; END IF;
    SELECT id INTO v_id FROM ordem_servico
    WHERE alerta_id = p_alerta_id AND status IN ('AGUARDANDO_ATRIBUICAO', 'ABERTA', 'EM_ANDAMENTO');
    IF FOUND THEN RETURN v_id; END IF;
    IF v_alerta.status = 'ENCERRADO' THEN RAISE EXCEPTION 'Alerta encerrado'; END IF;
    SELECT * INTO v_maquina FROM maquina WHERE id = v_alerta.maquina_id FOR SHARE;
    IF NOT v_maquina.ativa THEN RAISE EXCEPTION 'Maquina inativa'; END IF;
    PERFORM id FROM setor WHERE id = v_maquina.setor_id AND ativo FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Area inativa'; END IF;
    SELECT * INTO v_leitura FROM leitura WHERE id = v_alerta.ultima_leitura_id;
    IF v_leitura.status_analise <> 'OK' OR v_leitura.falha IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'O alerta deve apontar para uma leitura com analise OK e falha';
    END IF;

    INSERT INTO ordem_servico(alerta_id, maquina_id, setor_id, titulo, descricao,
        dados_abertura, prioridade, prazo_previsto, modo_atribuicao, criado_por)
    VALUES (p_alerta_id, v_maquina.id, v_maquina.setor_id,
        'Verificar indicacao de falha - ' || v_maquina.nome,
        coalesce(nullif(btrim(p_descricao), ''), 'Leitura com indicacao de falha pelo modelo. Diagnostico tecnico pendente.'),
        jsonb_build_object('maquina', to_jsonb(v_maquina), 'leitura', to_jsonb(v_leitura),
                           'alerta_id', p_alerta_id),
        p_prioridade, p_prazo, p_modo, p_usuario_id)
    RETURNING id INTO v_id;
    INSERT INTO os_historico(os_id, usuario_id, evento, dados)
    VALUES (v_id, p_usuario_id, 'CRIACAO', jsonb_build_object('alerta_id', p_alerta_id));
    PERFORM atribuir_ordem_servico(v_id, p_usuario_id, p_modo, p_tecnico_id);
    RETURN v_id;
END;
$$;

CREATE FUNCTION alterar_status_ordem_servico(
    p_os_id BIGINT,
    p_usuario_id BIGINT,
    p_status TEXT,
    p_relato TEXT DEFAULT NULL
) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE
    v_os ordem_servico%ROWTYPE;
    v_perfil TEXT;
    v_agora TIMESTAMPTZ;
BEGIN
    PERFORM pg_advisory_xact_lock(80321, 1);
    SELECT perfil INTO v_perfil FROM usuario WHERE id = p_usuario_id AND ativo;
    IF NOT FOUND THEN RAISE EXCEPTION 'Usuario inexistente ou inativo'; END IF;
    SELECT * INTO v_os FROM ordem_servico WHERE id = p_os_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Ordem de servico inexistente'; END IF;
    v_agora := clock_timestamp();
    IF v_os.status IN ('CONCLUIDA', 'CANCELADA') THEN RAISE EXCEPTION 'Ordem ja finalizada'; END IF;
    IF p_status IS NULL OR p_status NOT IN ('EM_ANDAMENTO', 'CONCLUIDA', 'CANCELADA') THEN
        RAISE EXCEPTION 'Destino de status invalido';
    END IF;
    IF p_status = 'CANCELADA' THEN
        IF v_perfil NOT IN ('GESTOR', 'ADMIN') THEN RAISE EXCEPTION 'Cancelamento exige gestor ou administrador'; END IF;
    ELSE
        IF NOT (v_perfil IN ('GESTOR', 'ADMIN') OR
                (v_perfil = 'TECNICO' AND v_os.tecnico_id IS NOT DISTINCT FROM p_usuario_id)) THEN
            RAISE EXCEPTION 'Usuario nao autorizado para esta ordem';
        END IF;
        IF (p_status = 'EM_ANDAMENTO' AND v_os.status <> 'ABERTA')
           OR (p_status = 'CONCLUIDA' AND v_os.status <> 'EM_ANDAMENTO') THEN
            RAISE EXCEPTION 'Transicao de status invalida';
        END IF;
    END IF;
    IF p_status IN ('CONCLUIDA', 'CANCELADA') AND coalesce(btrim(p_relato), '') = '' THEN
        RAISE EXCEPTION 'Informe o relato de conclusao ou o motivo do cancelamento';
    END IF;
    UPDATE ordem_servico SET
        status = p_status, atualizada_em = v_agora,
        iniciada_em = CASE WHEN p_status = 'EM_ANDAMENTO' THEN v_agora ELSE iniciada_em END,
        concluida_em = CASE WHEN p_status = 'CONCLUIDA' THEN v_agora ELSE NULL END,
        cancelada_em = CASE WHEN p_status = 'CANCELADA' THEN v_agora ELSE NULL END,
        relato_final = CASE WHEN p_status IN ('CONCLUIDA', 'CANCELADA') THEN p_relato ELSE relato_final END
    WHERE id = p_os_id;
    INSERT INTO os_historico(os_id, usuario_id, evento, dados)
    VALUES (p_os_id, p_usuario_id, 'STATUS', jsonb_build_object(
        'anterior', v_os.status, 'atual', p_status, 'relato', p_relato));
END;
$$;

REVOKE ALL ON FUNCTION criar_ordem_servico(BIGINT, BIGINT, TEXT, BIGINT, TEXT, TIMESTAMPTZ, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION atribuir_ordem_servico(BIGINT, BIGINT, TEXT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION alterar_status_ordem_servico(BIGINT, BIGINT, TEXT, TEXT) FROM PUBLIC;
