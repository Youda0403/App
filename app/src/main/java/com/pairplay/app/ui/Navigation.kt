package com.pairplay.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pairplay.app.ui.screens.CharacterEditScreen
import com.pairplay.app.ui.screens.CharactersScreen
import com.pairplay.app.ui.screens.HomeScreen
import com.pairplay.app.ui.screens.OnboardingScreen
import com.pairplay.app.ui.screens.OverlaySettingsScreen
import com.pairplay.app.ui.screens.RelationshipScreen
import com.pairplay.app.ui.screens.SceneEditScreen
import com.pairplay.app.ui.screens.SceneListScreen
import com.pairplay.app.ui.screens.SizeMatchScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHARACTERS = "characters"
    const val CHARACTER_EDIT = "character/{characterId}"
    const val SETTINGS = "settings"
    const val SIZE_MATCH = "size_match"
    const val RELATIONSHIP = "relationship"
    const val SCENES = "scenes"
    const val SCENE_EDIT = "scene/{sceneId}"

    fun characterEdit(id: Long) = "character/$id"
    fun sceneEdit(id: Long) = "scene/$id"
}

@Composable
fun PairPlayNavHost(viewModel: PairPlayViewModel) {
    val navController = rememberNavController()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val start = if (state.settings.onboardingCompleted) Routes.HOME else Routes.ONBOARDING

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                state = state,
                viewModel = viewModel,
                onFinished = {
                    viewModel.setOnboardingCompleted()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                state = state,
                viewModel = viewModel,
                onOpenCharacters = { navController.navigate(Routes.CHARACTERS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSizeMatch = { navController.navigate(Routes.SIZE_MATCH) },
                onOpenRelationship = { navController.navigate(Routes.RELATIONSHIP) },
                onOpenScenes = { navController.navigate(Routes.SCENES) }
            )
        }

        composable(Routes.RELATIONSHIP) {
            RelationshipScreen(
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SCENES) {
            SceneListScreen(
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.sceneEdit(id)) }
            )
        }

        composable(
            route = Routes.SCENE_EDIT,
            arguments = listOf(navArgument("sceneId") { type = NavType.LongType })
        ) { entry ->
            SceneEditScreen(
                sceneId = entry.arguments?.getLong("sceneId") ?: -1L,
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SIZE_MATCH) {
            SizeMatchScreen(
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.CHARACTERS) {
            CharactersScreen(
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.characterEdit(id)) }
            )
        }

        composable(
            route = Routes.CHARACTER_EDIT,
            arguments = listOf(navArgument("characterId") { type = NavType.LongType })
        ) { entry ->
            val characterId = entry.arguments?.getLong("characterId") ?: -1L
            CharacterEditScreen(
                characterId = characterId,
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            OverlaySettingsScreen(
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
