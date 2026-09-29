package labeling;

import model.Release;
import model.Ticket;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/** Esempi piccoli e controllabili a mano per difendere formula e casi limite. */
class ProportionCalculatorTest {
    private final ProportionCalculator calculator = new ProportionCalculator();
    private final List<Release> releases = List.of(
            release(1, "1.0", "2020-01-01"), release(2, "1.1", "2020-02-01"),
            release(3, "1.2", "2020-03-01"), release(4, "1.3", "2020-04-01"),
            release(5, "1.4", "2020-05-01"));

    @Test
    void calculatesTotalUsingKnownAffectedVersion() {
        Ticket ticket = ticket("STORM-1", "2020-01-15", "1.4");
        // OV=2, IV=1, FV=5 -> P=(5-1)/(5-2)=4/3. P > 1 e' valido.
        OptionalDouble actual = calculator.calculateProportionTotal(List.of(ticket), releases,
                Map.of("STORM-1", releases.get(0)));
        assertTrue(actual.isPresent());
        assertEquals(4.0 / 3.0, actual.getAsDouble(), 0.000001);
    }

    @Test
    void ignoresTicketWhenNoKnownInjectedVersionIsAvailable() {
        Ticket ticket = ticket("STORM-2", "2020-01-15", "1.4");
        assertTrue(calculator.calculateProportionTotal(List.of(ticket), releases, Map.of()).isEmpty());
    }

    @Test
    void estimatesAReleaseStrictlyBeforeFixedVersion() {
        Ticket ticket = ticket("STORM-3", "2020-01-15", "1.4");
        // OV=2, FV=5, P=1.5 -> IV=5-(5-2)*1.5=0.5, arrotondato e limitato alla release 1.
        Optional<Release> estimate = calculator.estimateInjectedVersion(ticket, releases, OptionalDouble.of(1.5));
        assertEquals("1.0", estimate.orElseThrow().name());
    }

    @Test
    void refusesAnInvalidTemporalInterval() {
        OptionalDouble result = calculator.calculateSingleProportion(Optional.of(releases.get(4)),
                Optional.of(releases.get(3)), Optional.of(releases.get(1)));
        assertTrue(result.isEmpty());
    }

    @Test
    void distinguishesFutureFixedVersionFromUnresolvedFixedVersion() {
        Ticket future = ticket("STORM-4", "2020-01-15", "2.0");
        Ticket unresolved = ticket("STORM-5", "2020-01-15", "1.0.7");

        assertEquals(ProportionCalculator.FixedVersionStatus.FV_AFTER_CORPUS,
                calculator.resolveFixedVersion(future, releases).status());
        assertEquals(ProportionCalculator.FixedVersionStatus.FV_UNRESOLVED,
                calculator.resolveFixedVersion(unresolved, releases).status());
    }

    @Test
    void neverEstimatesUsingAnUnresolvedFixedVersion() {
        Ticket unresolved = ticket("STORM-6", "2020-01-15", "1.0.7");
        assertTrue(calculator.estimateInjectedVersion(unresolved, releases, OptionalDouble.of(1.5)).isEmpty());
    }

    private static Release release(int index, String name, String date) {
        return new Release(index, String.valueOf(index), name, LocalDate.parse(date), false);
    }
    private static Ticket ticket(String key, String creation, String fixed) {
        return new Ticket(key, key, "Bug", "Resolved", "Fixed", LocalDate.parse(creation),
                LocalDate.parse("2020-04-15"), List.of(), List.of(fixed));
    }
}
