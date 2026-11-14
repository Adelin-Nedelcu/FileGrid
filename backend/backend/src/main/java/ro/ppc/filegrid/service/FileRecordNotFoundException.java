package ro.ppc.filegrid.service;

import java.util.UUID;

public class FileRecordNotFoundException extends RuntimeException {

    public FileRecordNotFoundException(UUID id) {
        super("Fisierul " + id + " nu exista sau nu a fost confirmat.");
    }
}
