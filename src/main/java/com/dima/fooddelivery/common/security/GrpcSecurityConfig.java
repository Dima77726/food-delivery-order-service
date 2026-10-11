package com.dima.fooddelivery.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.grpc.server.security.SslContextPreAuthenticationExtractor;
import org.springframework.security.core.userdetails.User;

import java.util.List;

/**
 * Кто вправе вызывать gRPC-сервер этого сервиса.
 *
 * <p><b>Взаимный TLS (mTLS).</b> С профилем {@code grpc-tls} соединение шифруется, и обе
 * стороны предъявляют сертификаты, подписанные общим удостоверяющим центром. Сервер не просто
 * знает, что к нему пришёл «кто-то из своих», — он знает, кто именно: имя сервиса записано
 * в CN сертификата. По этому имени и решается доступ: {@code allowed-clients} — список
 * сервисов, которым этот вызов положен. Свой сертификат у постороннего, подписанный тем же
 * CA, даёт PERMISSION_DENIED; без сертификата не пройдёт даже рукопожатие TLS.
 *
 * <p><b>Без mTLS</b> (запуск из IDE, тесты без профиля) доступ открыт, и об этом пишется
 * предупреждение. Это осознанная уступка удобству разработки: требовать сертификаты
 * от ноутбука разработчика — значит заставить его выключить проверку совсем.
 *
 * <p>Почему класс вообще нужен: Spring gRPC, увидев Spring Security в classpath, закрывает
 * gRPC-сервер аутентификацией по умолчанию, и без явного правила любой вызов получил бы
 * UNAUTHENTICATED.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GrpcSecurityConfig.GrpcClientsProperties.class)
public class GrpcSecurityConfig {

    /** Префикс полномочия, которое получает сервис по имени из сертификата. */
    static final String SERVICE_AUTHORITY_PREFIX = "SERVICE_";

    @Bean
    @GlobalServerInterceptor
    AuthenticationProcessInterceptor grpcSecurityInterceptor(GrpcSecurity grpc, GrpcClientsProperties clients)
            throws Exception {
        if (!clients.mtls()) {
            log.warn("gRPC-сервер открыт без mTLS: любой, кто дотянется до порта, может его вызвать. "
                    + "Допустимо только для разработки — в compose включён профиль grpc-tls");

            return grpc.authorizeRequests(requests -> requests.allRequests().permitAll()).build();
        }

        String[] allowed = clients.allowedClients().stream()
                .map(client -> SERVICE_AUTHORITY_PREFIX + client)
                .toArray(String[]::new);

        log.info("gRPC-сервер принимает вызовы только по mTLS и только от: {}", clients.allowedClients());

        return grpc
                // Имя сервиса — CN клиентского сертификата. Сертификат к этому моменту уже
                // проверен TLS: подписан нашим CA, не просрочен.
                .authenticationExtractor(new SslContextPreAuthenticationExtractor())
                .preauth(preauth -> preauth.userDetailsService(name -> User.withUsername(name)
                        .password("")
                        .authorities(SERVICE_AUTHORITY_PREFIX + name)
                        .build()))
                .authorizeRequests(requests -> requests.allRequests().hasAnyAuthority(allowed))
                .build();
    }

    /**
     * @param mtls           требовать ли клиентский сертификат и сверять ли имя сервиса
     * @param allowedClients имена сервисов (CN сертификатов), которым разрешены вызовы
     */
    @ConfigurationProperties(prefix = "app.grpc.security")
    public record GrpcClientsProperties(boolean mtls, List<String> allowedClients) {

        public GrpcClientsProperties {
            allowedClients = allowedClients == null ? List.of() : List.copyOf(allowedClients);
        }
    }
}
