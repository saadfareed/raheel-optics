package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.PasswordRules
import pk.raheeloptics.orders.core.RegistrationRules
import org.junit.Assert.assertThrows
import org.junit.Test

class PasswordRulesTest {
    @Test fun registrationRequiresConfirmationBeforeAccountCreation() {
        assertThrows(IllegalArgumentException::class.java) {RegistrationRules.validate("too-short","too-short")}
        assertThrows(IllegalArgumentException::class.java) {RegistrationRules.validate("long-enough-password","different-password")}
        RegistrationRules.validate("long-enough-password","long-enough-password")
    }
    @Test fun changingPasswordRequiresCurrentPasswordAndDifferentStrongReplacement() {
        assertThrows(IllegalArgumentException::class.java) {PasswordRules.validate("", "a-new-password")}
        assertThrows(IllegalArgumentException::class.java) {PasswordRules.validate("old-password", "short")}
        assertThrows(IllegalArgumentException::class.java) {PasswordRules.validate("unchanged-password", "unchanged-password")}
        PasswordRules.validate("old-password", "a-new-long-password")
    }
}
