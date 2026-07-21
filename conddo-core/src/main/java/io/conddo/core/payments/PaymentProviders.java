package io.conddo.core.payments;

import io.conddo.core.domain.PaymentIntent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry that looks up a {@link PaymentProvider} by name. Spring
 * injects every provider bean and this component indexes them so
 * feature code and the webhook controller can route by
 * {@link PaymentIntent#getProvider()}.
 */
@Component
public class PaymentProviders {

    private final Map<String, PaymentProvider> byName;

    public PaymentProviders(List<PaymentProvider> providers) {
        this.byName = providers.stream()
                .collect(Collectors.toMap(PaymentProvider::providerName, Function.identity()));
    }

    /** Look up the provider that handles {@code name}, or throw. */
    public PaymentProvider require(String name) {
        PaymentProvider p = byName.get(name);
        if (p == null) {
            throw new IllegalArgumentException("Unknown payment provider: " + name);
        }
        return p;
    }
}
