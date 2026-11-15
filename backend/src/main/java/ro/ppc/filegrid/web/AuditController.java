package ro.ppc.filegrid.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.security.AccessPolicy;
import ro.ppc.filegrid.security.ForbiddenException;
import ro.ppc.filegrid.service.AuditLog;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLog audit;
    private final AccessPolicy access;
    private final FileGridProperties props;

    public AuditController(AuditLog audit, AccessPolicy access, FileGridProperties props) {
        this.audit = audit;
        this.access = access;
        this.props = props;
    }

    @GetMapping
    public PageResponse<AuditLog.Entry> search(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        List<DataCategory> categories = access.readable(access.currentRoles()).stream()
                .filter(category -> props.policyFor(category).auditAccess())
                .toList();
        if (categories.isEmpty()) {
            throw new ForbiddenException("Contul " + access.currentUser() + " nu are acces la jurnalul de audit.");
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 200);
        AuditLog.Result result = audit.search(categories, action, q, safePage, safeSize);
        return new PageResponse<>(result.items(), result.total(), safePage, safeSize);
    }
}
