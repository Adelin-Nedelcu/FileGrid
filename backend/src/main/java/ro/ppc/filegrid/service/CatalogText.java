package ro.ppc.filegrid.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StoredFile;
import ro.ppc.filegrid.domain.StoredFileRepository;

import java.util.List;
import java.util.UUID;

@Component
public class CatalogText {

    private static final Logger log = LoggerFactory.getLogger(CatalogText.class);

    private final EncryptionService encryption;
    private final FileGridProperties props;
    private final StoredFileRepository repository;
    private final JdbcTemplate jdbc;

    public CatalogText(EncryptionService encryption, FileGridProperties props, StoredFileRepository repository,
                       JdbcTemplate jdbc) {
        this.encryption = encryption;
        this.props = props;
        this.repository = repository;
        this.jdbc = jdbc;
    }

    public void apply(StoredFile file, String name, String description) {
        if (props.policyFor(file.getCategory()).encryptAtRest()) {
            file.setText(encryption.sealText(name), encryption.sealText(description),
                    encryption.searchTokens(name, description));
        } else {
            file.setText(name, description, null);
        }
    }

    public String name(StoredFile file) {
        return encryption.openText(file.getOriginalName());
    }

    public String description(StoredFile file) {
        return encryption.openText(file.getDescription());
    }

    @EventListener(ApplicationReadyEvent.class)
    void protectExisting() {
        List<String> categories = props.policies().entrySet().stream()
                .filter(e -> e.getValue().encryptAtRest())
                .map(e -> e.getKey().name())
                .toList();
        for (String category : categories) {
            List<UUID> ids = jdbc.queryForList("""
                    SELECT id FROM stored_file
                    WHERE category = ? AND original_name NOT LIKE 'enc1:%'""", UUID.class, category);
            int done = 0;
            for (StoredFile file : repository.findAllById(ids)) {
                try {
                    apply(file, file.getOriginalName(), file.getDescription());
                    repository.save(file);
                    done++;
                } catch (OptimisticLockingFailureException e) {
                    log.debug("Fisierul {} a fost modificat in paralel; il criptez la urmatoarea pornire", file.getId());
                }
            }
            if (done > 0) {
                log.info("Am criptat numele si descrierea pentru {} fisiere {} existente", done, DataCategory.valueOf(category));
            }
        }
    }
}
