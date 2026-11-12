package pk.raheeloptics.orders.core

object OnboardingPolicy {
    fun shouldShow(completed:Boolean,existingWorkspace:Boolean)=!completed && !existingWorkspace
}
