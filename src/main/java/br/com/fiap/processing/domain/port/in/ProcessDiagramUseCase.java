package br.com.fiap.processing.domain.port.in;

import br.com.fiap.processing.domain.model.SqsMessage;

public interface ProcessDiagramUseCase {

    void execute(SqsMessage message);
}
