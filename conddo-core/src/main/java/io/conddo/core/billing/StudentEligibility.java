package io.conddo.core.billing;

import java.util.List;

/**
 * Gate for the Student tier (Pricing v2 / V67). Students pay ₦3,000/mo — a
 * real discount — so we need some minimum evidence of student status. We
 * chose the lightest possible check: email suffix on a recognised academic
 * domain family. This covers Nigerian universities ({@code .edu.ng}), the
 * global {@code .edu} space, and the British {@code .ac.uk} / {@code .ac.*}
 * family that a chunk of Nigerian students on study abroad end up on.
 *
 * <p>What this deliberately does NOT do:
 * <ul>
 *   <li>Verify student status against the institution's registrar.</li>
 *   <li>Handle graduation — a Student subscriber's email still works long
 *       after they leave. We accept the abuse risk at ₦3k/mo (they'd save
 *       ₦24k/yr vs Starter — small enough to not chase).</li>
 *   <li>Accept alumni or gmail-with-a-student-photo. If the email doesn't
 *       end in an accepted suffix the answer is "no".</li>
 * </ul>
 *
 * <p>Future upgrade path: gate on manual review (upload a student ID to
 * MinIO, admin approves in {@code /admin/students-queue}). At that point
 * this class stays but becomes the fast-path — the manual queue is only
 * for people whose emails don't match.
 */
public final class StudentEligibility {

    /** Suffixes we currently accept as "student". Add or drop as needed —
     *  the list is deliberately narrow to keep the abuse surface small.
     *  All entries are lower-cased and start with a dot. */
    private static final List<String> ACADEMIC_SUFFIXES = List.of(
            ".edu",       // US + Nigerian universities that follow the US convention
            ".edu.ng",    // Nigerian public + private universities
            ".ac.ng",     // Nigerian academic domain style
            ".ac.uk",     // UK academic
            ".edu.gh",    // Ghanaian (many Nigerian students)
            ".ac.za"      // South African
    );

    private StudentEligibility() {}

    /** True when {@code email} looks academic per the suffix list. Case- and
     *  whitespace-tolerant. Null and blank return false. */
    public static boolean isEligible(String email) {
        if (email == null) return false;
        String normalised = email.trim().toLowerCase();
        if (normalised.isEmpty() || !normalised.contains("@")) return false;
        for (String suffix : ACADEMIC_SUFFIXES) {
            if (normalised.endsWith(suffix)) return true;
        }
        return false;
    }

    /** Throws {@link StudentVerificationRequiredException} if the email is
     *  not eligible. Callers should invoke this only when the tenant is
     *  selecting or already on the Student plan — never for other plans. */
    public static void assertEligible(String email) {
        if (!isEligible(email)) {
            throw new StudentVerificationRequiredException(email);
        }
    }

    /** True when {@code planName} is the Student tier (case-insensitive). */
    public static boolean isStudentPlan(String planName) {
        return planName != null && "student".equalsIgnoreCase(planName.trim());
    }

    public static final class StudentVerificationRequiredException extends RuntimeException {
        public StudentVerificationRequiredException(String email) {
            super("Student plan requires an academic email (.edu, .edu.ng, .ac.uk, etc.). Got: " + email);
        }
    }
}
