package ro.ppc.filegrid.web;

import org.springframework.stereotype.Component;
import ro.ppc.filegrid.domain.StoredFile;
import ro.ppc.filegrid.service.CatalogText;

@Component
public class FileViews {

    private final CatalogText text;

    public FileViews(CatalogText text) {
        this.text = text;
    }

    public FileResponse of(StoredFile file) {
        return of(file, false);
    }

    public FileResponse of(StoredFile file, boolean duplicate) {
        return FileResponse.from(file, text.name(file), text.description(file), duplicate);
    }

    public String name(StoredFile file) {
        return text.name(file);
    }
}
