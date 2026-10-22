package com.rendox.grocerygenius.feature.dashboardscreen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidViewBinding
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rendox.grocerygenius.R
import com.rendox.grocerygenius.databinding.ListRecyclerviewBinding
import com.rendox.grocerygenius.feature.dashboardscreen.recyclerview.DashboardRecyclerViewAdapter
import com.rendox.grocerygenius.feature.webdavsync.SyncButton
import com.rendox.grocerygenius.model.GroceryList
import com.rendox.grocerygenius.ui.helpers.ObserveUiEvent
import com.rendox.grocerygenius.ui.theme.GroceryGeniusTheme
import com.rendox.grocerygenius.ui.theme.TopAppBarActionsHorizontalPadding
import com.rendox.grocerygenius.ui.theme.TopAppBarSmallHeight

@Composable
fun GroceryListsDashboardRoute(
    viewModel: GroceryListsDashboardViewModel = hiltViewModel(),
    navigateToGroceryListScreen: (String) -> Unit,
    navigateToSettingsScreen: () -> Unit
) {
    val screenState by viewModel.groceryListsFlow.collectAsStateWithLifecycle()
    val navigateToGroceryListEvent by viewModel.navigateToGroceryListEvent.collectAsStateWithLifecycle()
    ObserveUiEvent(navigateToGroceryListEvent) { groceryListId ->
        navigateToGroceryListScreen(groceryListId)
    }

    GroceryListsDashboardScreen(
        groceryLists = screenState,
        onIntent = viewModel::onIntent,
        navigateToSettingsScreen = navigateToSettingsScreen,
        navigateToGroceryListScreen = navigateToGroceryListScreen,
        syncButton = { SyncButton() }
    )
}

@Composable
fun GroceryListsDashboardScreen(
    groceryLists: List<GroceryList>,
    onIntent: (GroceryListsDashboardUiIntent) -> Unit = {},
    navigateToSettingsScreen: () -> Unit = {},
    navigateToGroceryListScreen: (String) -> Unit = {},
    syncButton: @Composable () -> Unit = {}
) {
    var scrollState by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TopAppBarSmallHeight)
        ) {
            Text(
                modifier = Modifier.align(Alignment.Center),
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.titleLarge
            )
            Row(
                modifier = Modifier
                    .padding(end = TopAppBarActionsHorizontalPadding)
                    .align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                syncButton()
                IconButton(onClick = navigateToSettingsScreen) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings)
                    )
                }
            }
        }
        Image(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth()
                .aspectRatio(1376F / 768F)
                .clip(RoundedCornerShape(12.dp)),
            painter = painterResource(R.drawable.dashboard_header_image),
            contentDescription = null,
            contentScale = ContentScale.Crop
        )
        AndroidViewBinding(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            factory = { inflater, parent, attachToParent ->
                val binding = ListRecyclerviewBinding.inflate(inflater, parent, attachToParent)
                binding.listRecyclerview.addOnScrollListener(
                    object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(
                            recyclerView: RecyclerView,
                            dx: Int,
                            dy: Int
                        ) {
                            super.onScrolled(recyclerView, dx, dy)
                            scrollState += dy
                        }
                    }
                )
                binding.listRecyclerview.adapter = DashboardRecyclerViewAdapter(
                    recyclerView = binding.listRecyclerview,
                    groceryLists = groceryLists,
                    updateLists = { newValue ->
                        onIntent(GroceryListsDashboardUiIntent.OnUpdateGroceryLists(newValue))
                    },
                    onItemClicked = navigateToGroceryListScreen,
                    onAdderItemClicked = {
                        onIntent(GroceryListsDashboardUiIntent.OnAdderItemClick)
                    }
                )
                binding
            },
            update = {
                val adapter = listRecyclerview.adapter as DashboardRecyclerViewAdapter
                val isInitialUpdate = adapter.groceryLists.isEmpty()
                adapter.updateGroceryLists(groceryLists)

                // this code is required to keep scroll position when updating the list
                // otherwise recyclerView will automatically follow the AdderItem and scroll
                // to bottom once the list is fetched
                if (isInitialUpdate) {
                    val layoutManager = listRecyclerview.layoutManager as LinearLayoutManager
                    layoutManager.scrollToPositionWithOffset(0, -scrollState)
                }
            }
        )
    }
}

@Preview
@Composable
private fun GroceryListsDashboardPreview() {
    GroceryGeniusTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GroceryListsDashboardScreen(
                groceryLists = sampleDashboard
            )
        }
    }
}

val sampleDashboard = List(20) {
    GroceryList(
        id = it.toString(),
        name = "List $it",
        numOfGroceries = it
    )
}