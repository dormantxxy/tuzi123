package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FolderCode
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.workspace.WorkspaceType
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkspacePage(vm: WorkspaceVM = koinViewModel()) {
    val navController = LocalNavController.current
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val toolchainExecutor: me.rerere.rikkahub.data.localtools.ToolchainExecutor = org.koin.compose.koinInject()
    // 是否有 root/Shizuku（工具链工作区依赖它）
    // Shizuku 授权状态变化后要重新评估，否则刚授权的用户仍看到"禁用"
    val shizukuGranted by me.rerere.rikkahub.data.localtools.ShizukuHelper.permissionGranted.collectAsStateWithLifecycle()
    val hasPrivilege by produceState(initialValue = true, shizukuGranted) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) { toolchainExecutor.hasPrivilege() }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showTypeSheet by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var createType by rememberSaveable { mutableStateOf(WorkspaceType.LOCAL) }
    var editTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.workspace_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showTypeSheet = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (workspaces.isEmpty()) {
                item {
                    ExtensionEmptyState(
                        message = stringResource(R.string.workspace_page_empty),
                        description = stringResource(R.string.workspace_page_empty_desc),
                        icon = HugeIcons.FolderCode,
                    )
                }
            }

            items(workspaces, key = { it.id }) { workspace ->
                WorkspaceItem(
                    privilegeAvailable = hasPrivilege,
                    workspace = workspace,
                    onRename = { editTarget = workspace },
                    onDelete = { deleteTarget = workspace },
                    onOpen = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                )
            }
        }
    }

    if (showTypeSheet) {
        ModalBottomSheet(onDismissRequest = { showTypeSheet = false }) {
            CardGroup(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                title = { Text(stringResource(R.string.workspace_page_create)) },
            ) {
                item(
                    onClick = {
                        showTypeSheet = false
                        createType = WorkspaceType.LOCAL
                        showAddDialog = true
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.FolderCode) },
                    supportingContent = { Text(stringResource(R.string.workspace_page_type_local_desc)) },
                    headlineContent = { Text(stringResource(R.string.workspace_page_type_local)) },
                )
                item(
                    onClick = {
                        showTypeSheet = false
                        navController.navigate(Screen.WorkspaceSshEditor())
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.CloudServer) },
                    supportingContent = { Text(stringResource(R.string.workspace_page_type_ssh_desc)) },
                    headlineContent = { Text(stringResource(R.string.workspace_page_type_ssh)) },
                )
                item(
                    onClick = {
                        if (!hasPrivilege) {
                            toaster.show(message = "需要 root 或 Shizuku 权限才能创建工具链工作区", type = ToastType.Warning)
                        } else {
                            showTypeSheet = false
                            createType = WorkspaceType.TOOLCHAIN
                            showAddDialog = true
                        }
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.ComputerTerminal01) },
                    supportingContent = {
                        Text(
                            if (hasPrivilege) "使用已装扩展聚合的工具链环境执行命令，文件放在 /storage/emulated/0/RikkaHub/workspaces"
                            else "需要 root 或 Shizuku 权限"
                        )
                    },
                    headlineContent = { Text("工具链工作区") },
                )
            }
        }
    }

    if (showAddDialog) {
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_create),
            initialName = "",
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                vm.create(name, createType)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { workspace ->
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_rename),
            initialName = workspace.name,
            existingNames = workspaces.filter { it.id != workspace.id }.map { it.name.trim() }.toSet(),
            onDismiss = { editTarget = null },
            onConfirm = { name ->
                vm.rename(workspace, name)
                editTarget = null
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.workspace_page_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.delete(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.workspace_page_delete_confirm))
    }
}

@Composable
private fun WorkspaceItem(
    workspace: WorkspaceEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    privilegeAvailable: Boolean = true,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(
                when (workspace.workspaceType()) {
                    WorkspaceType.SSH -> HugeIcons.CloudServer
                    WorkspaceType.TOOLCHAIN -> HugeIcons.ComputerTerminal01
                    WorkspaceType.LOCAL -> HugeIcons.FolderCode
                }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = workspace.statusLabel(privilegeAvailable = privilegeAvailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.common_rename),
                        icon = HugeIcons.PencilEdit01,
                        onClick = onRename,
                    ),
                    ItemAction(
                        text = stringResource(R.string.common_delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}

@Composable
private fun EditWorkspaceDialog(
    title: String,
    initialName: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && trimmedName in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workspace_page_name)) },
                singleLine = true,
                isError = isDuplicate,
                supportingText = if (isDuplicate) {
                    { Text(stringResource(R.string.workspace_page_name_duplicate)) }
                } else null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = name.isNotBlank() && !isDuplicate,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
