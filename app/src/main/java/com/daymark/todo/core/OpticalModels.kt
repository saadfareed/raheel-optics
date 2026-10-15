package com.daymark.todo.core

import kotlinx.serialization.Serializable

@Serializable enum class OrderStatus { NEW, IN_PRODUCTION, READY, DELIVERED, CANCELLED }
@Serializable enum class PrismBase { UP, DOWN, IN, OUT }
@Serializable data class EyePrescription(val sphere:String="",val cylinder:String?=null,val axis:Int?=null,val prism:String?=null,val base:PrismBase?=null)
@Serializable data class Prescription(val distanceRight:EyePrescription=EyePrescription(),val distanceLeft:EyePrescription=EyePrescription(),val addRight:String?=null,val addLeft:String?=null,val pd:String?=null)
@Serializable data class OrderAmounts(val framePaisa:Long=0,val lensPaisa:Long=0,val extrasPaisa:Long=0,val discountPaisa:Long=0,val taxPaisa:Long=0) {
    val totalPaisa:Long get()=(framePaisa+lensPaisa+extrasPaisa-discountPaisa+taxPaisa).coerceAtLeast(0)
}
@Serializable data class Payment(val id:String,val amountPaisa:Long,val receivedAt:Long=System.currentTimeMillis())
@Serializable data class FrameDetails(val localImageUri:String?=null,val imageKey:String?=null)

object PakistanPhone {
    fun normalize(value:String):String? {
        val compact=value.trim().replace(Regex("[\\s()\\-]"),"")
        val national=when {compact.startsWith("+92")->compact.drop(3);compact.startsWith("0092")->compact.drop(4);compact.startsWith("92")->compact.drop(2);compact.startsWith("0")->compact.drop(1);else->compact}
        return national.takeIf {it.matches(Regex("3\\d{9}"))}?.let {"+92$it"}
    }
}

object OpticalRules {
    fun validateEye(eye:EyePrescription) {
        fun decimal(value:String?,label:String)=value?.takeIf {it.isNotBlank()}?.removePrefix("+")?.toBigDecimalOrNull() ?: if(value.isNullOrBlank()) null else throw IllegalArgumentException("Enter a valid $label value.")
        decimal(eye.sphere,"SPH")?.let {require(it>=(-30).toBigDecimal() && it<=30.toBigDecimal()) {"SPH must be between -30.00 and +30.00."}}
        val cylinder=decimal(eye.cylinder,"CYL")
        require((cylinder==null)==(eye.axis==null)) {"AXIS is required when CYL is added."}
        eye.axis?.let {require(it in 1..180) {"AXIS must be between 1 and 180."}}
        val prism=decimal(eye.prism,"PRISM")
        require((prism==null)==(eye.base==null)) {"BASE is required when PRISM is added."}
    }
}
