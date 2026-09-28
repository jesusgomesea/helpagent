package br.com.rdamasio.helpagent.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import br.com.rdamasio.helpagent.cotacao.FalhaColeta;
import br.com.rdamasio.helpagent.extracao.FalhaIa;

/** Erros no formato RFC 9457 (ProblemDetail); {@code problemas} traz a lista que o frontend exibe. */
@RestControllerAdvice
public class TratadorErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorErros.class);

    @ExceptionHandler(ErroNegocio.class)
    ProblemDetail negocio(ErroNegocio e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        pd.setProperty("problemas", e.problemas());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacao(MethodArgumentNotValidException e) {
        List<String> problemas = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "Dados inválidos");
        pd.setProperty("problemas", problemas);
        return pd;
    }

    @ExceptionHandler(NaoEncontrado.class)
    ProblemDetail naoEncontrado(NaoEncontrado e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail uploadGrande(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "Os arquivos enviados passam do limite permitido.");
    }

    /** Coleta da cotação voltou vazia: {@code problemas} traz os avisos (bloqueio, termo sem resultado...). */
    @ExceptionHandler(FalhaColeta.class)
    ProblemDetail coleta(FalhaColeta e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        pd.setProperty("problemas", e.avisos());
        return pd;
    }

    @ExceptionHandler(FalhaIa.class)
    ProblemDetail ia(FalhaIa e) {
        log.warn("Extração com IA falhou após {} tentativa(s): {}", e.tentativas(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        pd.setProperty("tentativas", e.tentativas());
        return pd;
    }
}
