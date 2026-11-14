package ro.ppc.filegrid.service;

import ro.ppc.filegrid.domain.DataCategory;

public class CapacityExceededException extends RuntimeException {

    private final DataCategory category;

    public CapacityExceededException(DataCategory category) {
        super("Categoria " + category + " este la capacitate maxima. Reincercati in cateva secunde.");
        this.category = category;
    }

    public DataCategory getCategory() {
        return category;
    }
}
