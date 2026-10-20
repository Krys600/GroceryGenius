package com.rendox.grocerygenius.feature.webdavsync

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import com.rendox.grocerygenius.ui.GroceryGeniusTransition

const val WEBDAV_SYNC_ROUTE = "webdav_sync_route"

fun NavController.navigateToWebDavSync(navOptions: (NavOptionsBuilder.() -> Unit) = {}) {
    this.navigate(
        route = WEBDAV_SYNC_ROUTE,
        builder = navOptions
    )
}

fun NavGraphBuilder.webDavSyncScreen(navigateBack: () -> Unit) {
    composable(
        route = WEBDAV_SYNC_ROUTE,
        enterTransition = { GroceryGeniusTransition.SlideInVertically },
        exitTransition = { GroceryGeniusTransition.SlideOutVertically }
    ) {
        WebDavSyncRoute(
            navigateBack = navigateBack
        )
    }
}