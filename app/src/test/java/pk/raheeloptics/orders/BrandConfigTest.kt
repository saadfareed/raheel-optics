package pk.raheeloptics.orders

import pk.raheeloptics.orders.branding.BrandConfig
import org.junit.Assert.assertThrows
import org.junit.Test

class BrandConfigTest {
    private val project="https://project.supabase.co"
    @Test fun acceptsBundledBrandAndOwnedPublicStorageLogo() {
        BrandConfig("Raheel Optics","Clear vision").validate(project)
        BrandConfig("Raheel Optics","Clear vision","$project/storage/v1/object/public/branding/logo.png").validate(project)
    }
    @Test fun rejectsExternalInsecureAndCredentialBearingLogos() {
        listOf("http://project.supabase.co/storage/v1/object/public/branding/logo.png",
            "https://tracking.example.com/logo.png",
            "$project/storage/v1/object/public/tasks/photo.png",
            "$project/storage/v1/object/public/branding/%2e%2e/private.png",
            "https://user:pass@project.supabase.co/storage/v1/object/public/branding/logo.png"
        ).forEach {url ->assertThrows(IllegalArgumentException::class.java) {BrandConfig("Raheel Optics","Clear vision",url).validate(project)}}
    }
    @Test fun rejectsOversizedAndControlCharacterNames() {
        assertThrows(IllegalArgumentException::class.java) {BrandConfig("x".repeat(41),"").validate(project)}
        assertThrows(IllegalArgumentException::class.java) {BrandConfig("Day\nmark","").validate(project)}
    }
}
