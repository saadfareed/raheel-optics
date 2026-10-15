package com.daymark.todo.core

object OnboardingPolicy {
    fun shouldShow(completed:Boolean,existingWorkspace:Boolean)=!completed && !existingWorkspace
}
