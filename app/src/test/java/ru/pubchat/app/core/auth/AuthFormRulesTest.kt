package ru.pubchat.app.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFormRulesTest {
    @Test fun loginRequiresIdentifierAndAllowsLegacyShortPassword() {
        assertFalse(AuthFormRules.canLogin("", "secret"))
        assertFalse(AuthFormRules.canLogin("   ", "secret"))
        assertFalse(AuthFormRules.canLogin("user", ""))
        assertTrue(AuthFormRules.canLogin("  user  ", "p"))
        assertFalse(AuthFormRules.canLogin("user", "a".repeat(129)))
        assertFalse(AuthFormRules.canLogin("a".repeat(256), "valid"))
    }

    @Test fun signupHonorsBackendPasswordLengthsAndHandlePattern() {
        assertFalse(AuthFormRules.canRegister("alex", "", "1234567"))
        assertTrue(AuthFormRules.canRegister("alex", "", "12345678"))
        assertTrue(AuthFormRules.canRegister("alex_2026.test", "Александр", "p".repeat(128)))
        assertFalse(AuthFormRules.canRegister("alex", "", "p".repeat(129)))
        assertFalse(AuthFormRules.canRegister("a", "", "12345678"))
        assertFalse(AuthFormRules.canRegister("a".repeat(33), "", "12345678"))
        assertFalse(AuthFormRules.canRegister("bad handle", "", "12345678"))
        assertFalse(AuthFormRules.canRegister("кириллица", "", "12345678"))
        assertFalse(AuthFormRules.canRegister("alex", "А".repeat(81), "12345678"))
    }

    @Test fun hintsArePreciseAndOptionalNameIsAccepted() {
        assertEquals("Пароль должен содержать от 8 до 128 символов.",
            AuthFormRules.registrationHint("alex", "", "123"))
        assertEquals("Логин: 3–32 символа, латиница, цифры, точка или подчёркивание.",
            AuthFormRules.registrationHint("bad handle", "", "12345678"))
        assertNull(AuthFormRules.registrationHint("alex", "", "12345678"))
        assertTrue(AuthFormRules.validHandle("alex.2026_"))
        assertFalse(AuthFormRules.validHandle("alex-hyphen"))
    }
    @Test fun confirmationMustMatchAndStrengthUpdatesWithInput() {
        assertFalse(AuthFormRules.canRegisterConfirmed("alex", "", "password123", ""))
        assertFalse(AuthFormRules.canRegisterConfirmed("alex", "", "password123", "other"))
        assertTrue(AuthFormRules.canRegisterConfirmed("alex", "", "password123", "password123"))
        assertFalse(AuthFormRules.passwordConfirmationMatches("secret", ""))
        assertTrue(AuthFormRules.passwordConfirmationMatches("secret", "secret"))
        assertEquals(0, AuthFormRules.passwordStrength(""))
        assertTrue(AuthFormRules.passwordStrength("StrongPassword12!") >
            AuthFormRules.passwordStrength("password"))
    }
}
