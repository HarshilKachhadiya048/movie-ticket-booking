package com.harshil.movieticketbooking.common.api;

/**
 * Single source of truth for every HTTP path exposed by the application.
 * <p>
 * Controllers carry no literal paths: each handler method is mapped with the
 * full constant from this class, and no class-level {@code @RequestMapping} is
 * used. That keeps one constant per endpoint, referenced exactly once, so the
 * URL surface can be read here in full and changed in one place. The same
 * constants are reused by {@code SecurityConfig} for authorization matching
 * and by the integration tests, so a path can never drift between routing,
 * security rules and tests.
 * <p>
 * Everything is a compile-time constant so the values are usable in
 * annotations.
 */
public final class ApiEndpoints {

    public static final String API_V1 = "/api/v1";
    public static final String ADMIN_V1 = API_V1 + "/admin";

    /** Everything below this prefix requires {@code ROLE_ADMIN}. */
    public static final String ADMIN_PATTERN = ADMIN_V1 + "/**";

    private ApiEndpoints() {
    }

    /** Authentication and the caller's own profile. */
    public static final class Auth {

        public static final String REGISTER = API_V1 + "/auth/register";
        public static final String ME = API_V1 + "/auth/me";

        private Auth() {
        }
    }

    public static final class Cities {

        public static final String ROOT = API_V1 + "/cities";
        public static final String BY_ID = ROOT + "/{cityId}";
        public static final String THEATERS = BY_ID + "/theaters";

        private Cities() {
        }
    }

    public static final class Movies {

        public static final String ROOT = API_V1 + "/movies";
        public static final String BY_ID = ROOT + "/{movieId}";

        private Movies() {
        }
    }

    public static final class Theaters {

        public static final String ROOT = API_V1 + "/theaters";
        public static final String BY_ID = ROOT + "/{theaterId}";
        public static final String SHOWS = BY_ID + "/shows";

        private Theaters() {
        }
    }

    public static final class Shows {

        public static final String ROOT = API_V1 + "/shows";
        public static final String BY_ID = ROOT + "/{showId}";
        public static final String SEATS = BY_ID + "/seats";

        private Shows() {
        }
    }

    public static final class Holds {

        public static final String ROOT = API_V1 + "/holds";

        private Holds() {
        }
    }

    public static final class Bookings {

        public static final String ROOT = API_V1 + "/bookings";
        public static final String BY_ID = ROOT + "/{bookingId}";
        public static final String PAYMENT = BY_ID + "/payment";
        public static final String CANCEL = BY_ID + "/cancel";

        private Bookings() {
        }
    }

    /** Admin catalogue and configuration management. */
    public static final class Admin {

        public static final class Cities {

            public static final String ROOT = ADMIN_V1 + "/cities";
            public static final String BY_ID = ROOT + "/{cityId}";

            private Cities() {
            }
        }

        public static final class Movies {

            public static final String ROOT = ADMIN_V1 + "/movies";
            public static final String BY_ID = ROOT + "/{movieId}";

            private Movies() {
            }
        }

        public static final class Theaters {

            public static final String ROOT = ADMIN_V1 + "/theaters";
            public static final String BY_ID = ROOT + "/{theaterId}";

            private Theaters() {
            }
        }

        public static final class Screens {

            public static final String ROOT = ADMIN_V1 + "/screens";
            public static final String BY_ID = ROOT + "/{screenId}";
            public static final String SEATS = BY_ID + "/seats";

            private Screens() {
            }
        }

        public static final class Shows {

            public static final String ROOT = ADMIN_V1 + "/shows";
            public static final String BY_ID = ROOT + "/{showId}";

            private Shows() {
            }
        }

        public static final class PricingRules {

            public static final String ROOT = ADMIN_V1 + "/pricing-rules";
            public static final String BY_ID = ROOT + "/{pricingRuleId}";

            private PricingRules() {
            }
        }

        public static final class DiscountCodes {

            public static final String ROOT = ADMIN_V1 + "/discount-codes";
            public static final String BY_ID = ROOT + "/{discountCodeId}";

            private DiscountCodes() {
            }
        }

        public static final class RefundPolicies {

            public static final String ROOT = ADMIN_V1 + "/refund-policies";
            public static final String BY_ID = ROOT + "/{refundPolicyId}";

            private RefundPolicies() {
            }
        }

        public static final class Users {

            public static final String ROOT = ADMIN_V1 + "/users";

            private Users() {
            }
        }

        private Admin() {
        }
    }
}
