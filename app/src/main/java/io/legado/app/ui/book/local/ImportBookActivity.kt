package io.legado.app.ui.book.local

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityImportBookBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.document.HandleFileContract
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导入本地书籍界面
 */
class ImportBookActivity : VMBaseActivity<ActivityImportBookBinding, ImportBookViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    ImportBookAdapter.CallBack,
    SelectActionBar.CallBack {

    override val binding by viewBinding(ActivityImportBookBinding::inflate)
    override val viewModel by viewModels<ImportBookViewModel>()
    private val bookFileRegex = Regex("(?i).*\\.(txt|epub|umd)")
    private var rootDoc: DocumentFile? = null
    private val subDocs = arrayListOf<DocumentFile>()
    private val adapter by lazy { ImportBookAdapter(this, this) }
    private var sdPath = FileUtils.getSdCardPath()
    private var path = sdPath
    private val selectFolder = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.importBookPath = uri.toString()
                initRootDoc()
            } else {
                uri.path?.let { path ->
                    AppConfig.importBookPath = path
                    initRootDoc()
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initEvent()
        initData()
        initRootDoc()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_book, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_select_folder -> selectFolder.launch(null)
            R.id.menu_scan_folder -> scanFolder()
            R.id.menu_import_file_name -> alertImportFileName()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_del_selection ->
                viewModel.deleteDoc(adapter.selectedUris) {
                    adapter.removeSelection()
                }
        }
        return false
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onClickMainAction() {
        viewModel.addToBookshelf(adapter.selectedUris) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun initView() {
        binding.layTop.setBackgroundColor(backgroundColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.selectActionBar.setMainActionText(R.string.add_to_shelf)
        binding.selectActionBar.inflateMenu(R.menu.import_book_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun initEvent() {
        binding.tvGoBack.setOnClickListener {
            goBackDir()
        }
    }

    private fun initData() {
        launch {
            appDb.bookDao.flowLocalUri().collect {
                adapter.upBookHas(it)
            }
        }
    }

    private fun initRootDoc() {
        val lastPath = AppConfig.importBookPath
        when {
            lastPath.isNullOrEmpty() -> {
                binding.tvEmptyMsg.visible()
                selectFolder.launch(null)
            }
            lastPath.isContentScheme() -> {
                val rootUri = Uri.parse(lastPath)
                rootDoc = DocumentFile.fromTreeUri(this, rootUri)
                if (rootDoc == null) {
                    binding.tvEmptyMsg.visible()
                    selectFolder.launch(null)
                } else {
                    subDocs.clear()
                    upPath()
                }
            }
            Build.VERSION.SDK_INT > Build.VERSION_CODES.Q -> {
                binding.tvEmptyMsg.visible()
                selectFolder.launch(null)
            }
            else -> {
                binding.tvEmptyMsg.visible()
                PermissionsCompat.Builder(this)
                    .addPermissions(*Permissions.Group.STORAGE)
                    .rationale(R.string.tip_perm_request_storage)
                    .onGranted {
                        rootDoc = null
                        subDocs.clear()
                        path = lastPath
                        upPath()
                    }
                    .request()
            }
        }
    }

    @Synchronized
    private fun upPath() {
        rootDoc?.let {
            upDocs(it)
        } ?: upFiles()
    }

    private fun upDocs(rootDoc: DocumentFile) {
        binding.tvEmptyMsg.gone()
        var path = rootDoc.name.toString() + File.separator
        var lastDoc = rootDoc
        for (doc in subDocs) {
            lastDoc = doc
            path = path + doc.name + File.separator
        }
        binding.tvPath.text = path
        adapter.selectedUris.clear()
        adapter.clearItems()
        launch(IO) {
            runCatching {
                val docList = DocumentUtils.listFiles(lastDoc.uri) { item ->
                    when {
                        item.name.startsWith(".") -> false
                        item.isDir -> true
                        else -> item.name.matches(bookFileRegex)
                    }
                }
                docList.sortWith(compareBy({ !it.isDir }, { it.name }))
                withContext(Main) {
                    adapter.setItems(docList)
                }
            }.onFailure {
                toastOnUi("获取文件列表出错\n${it.localizedMessage}")
            }
        }
    }

    private fun upFiles() {
        binding.tvEmptyMsg.gone()
        binding.tvPath.text = path.replace(sdPath, "SD")
        adapter.clearItems()
        launch(IO) {
            kotlin.runCatching {
                val docList = DocumentUtils.listFiles(path) {
                    when {
                        it.name.startsWith(".") -> false
                        it.isDir -> true
                        else -> it.name.matches(bookFileRegex)
                    }
                }
                docList.sortWith(compareBy({ !it.isDir }, { it.name }))
                withContext(Main) {
                    adapter.setItems(docList)
                }
            }.onFailure {
                toastOnUi("获取文件列表出错\n${it.localizedMessage}")
            }
        }
    }

    /**
     * 扫描当前文件夹
     */
    private fun scanFolder() {
        rootDoc?.let { doc ->
            adapter.clearItems()
            val lastDoc = subDocs.lastOrNull() ?: doc
            binding.refreshProgressBar.isAutoLoading = true
            launch(IO) {
                viewModel.scanDoc(lastDoc, true, find) {
                    launch {
                        binding.refreshProgressBar.isAutoLoading = false
                    }
                }
            }
        } ?: let {
            val lastPath = AppConfig.importBookPath
            if (lastPath.isNullOrEmpty()) {
                toastOnUi(R.string.empty_msg_import_book)
            } else {
                adapter.clearItems()
                val file = File(path)
                binding.refreshProgressBar.isAutoLoading = true
                launch(IO) {
                    viewModel.scanFile(file, true, find) {
                        launch {
                            binding.refreshProgressBar.isAutoLoading = false
                        }
                    }
                }
            }
        }
    }

    private fun alertImportFileName() {
        alert(R.string.import_file_name) {
            setMessage("""使用js返回一个json结构,{"name":"xxx", "author":"yyy"}""")
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "js"
                editView.setText(AppConfig.bookImportFileName)
            }
            customView { alertBinding.root }
            okButton {
                AppConfig.bookImportFileName = alertBinding.editView.text?.toString()
            }
            cancelButton()
        }
    }

    private val find: (docItem: FileDoc) -> Unit = {
        launch {
            adapter.addItem(it)
        }
    }

    @Synchronized
    override fun nextDoc(uri: Uri) {
        if (uri.toString().isContentScheme()) {
            subDocs.add(DocumentFile.fromSingleUri(this, uri)!!)
        } else {
            path = uri.path.toString()
        }
        upPath()
    }

    @Synchronized
    private fun goBackDir(): Boolean {
        if (rootDoc == null) {
            if (path != sdPath) {
                File(path).parent?.let {
                    path = it
                    upPath()
                    return true
                }
            }
            return false
        }
        return if (subDocs.isNotEmpty()) {
            subDocs.removeAt(subDocs.lastIndex)
            upPath()
            true
        } else {
            false
        }
    }

    override fun onBackPressed() {
        if (!goBackDir()) {
            super.onBackPressed()
        }
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selectedUris.size, adapter.checkableCount)
    }

}
