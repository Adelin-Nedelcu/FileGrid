package ro.ppc.filegrid.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.config.StatsProperties;
import ro.ppc.filegrid.security.AccessPolicy;
import ro.ppc.filegrid.security.ForbiddenException;
import ro.ppc.filegrid.stats.StatsService;
import ro.ppc.filegrid.stats.StorageBenchmark;

import java.util.Set;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService stats;
    private final StorageBenchmark benchmark;
    private final AccessPolicy access;
    private final StatsProperties props;

    public StatsController(StatsService stats, StorageBenchmark benchmark, AccessPolicy access, StatsProperties props) {
        this.stats = stats;
        this.benchmark = benchmark;
        this.access = access;
        this.props = props;
    }

    @GetMapping
    public StatsService.Stats stats(@RequestParam(defaultValue = "6") int hours) {
        require(props.viewRoles(), "statistici");
        return stats.stats(Math.clamp(hours, 1, 24 * 7));
    }

    @PostMapping("/benchmark")
    public StatsService.Stats runBenchmark(@RequestParam(defaultValue = "6") int hours) {
        require(props.benchmarkRoles(), "masuratoarea storage-urilor");
        benchmark.run();
        return stats.stats(Math.clamp(hours, 1, 24 * 7));
    }

    @ExceptionHandler(StorageBenchmark.AlreadyRunning.class)
    ResponseEntity<ProblemDetail> alreadyRunning(StorageBenchmark.AlreadyRunning e) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        body.setTitle("Masuratoare in curs");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private void require(Set<String> roles, String what) {
        if (access.currentRoles().stream().noneMatch(roles::contains)) {
            throw new ForbiddenException("Contul " + access.currentUser() + " nu are acces la " + what + ".");
        }
    }
}
