package com.mrgq.pdfviewer.server

/**
 * 웹 업로드 페이지 HTML (P18 7단계). 글은 [WebText] 사전, 언어는 브라우저의 `Accept-Language`([WebText.pickLanguage]).
 * 순수 JVM 코드 — `WebTextTest` 가 두 언어 페이지를 만들어 본다.
 */
object UploadPage {

    fun html(lang: WebLang): String {
        val text = WebText.of(lang)
        fun t(key: String): String = text[key] ?: error("WebText: no key " + key)
        // 놓이는 곳에 맞춘 이스케이프 — h: HTML 본문, j: JS '…' 문자열, jh: innerHTML 로 가는 JS '…', th: innerHTML 로 가는 JS `…`
        fun h(key: String) = escapeHtml(t(key))
        fun j(key: String) = escapeJs(t(key))
        fun jh(key: String) = escapeJs(escapeHtml(t(key)))
        fun th(key: String) = escapeTemplate(escapeHtml(t(key)))
        return """
                <!DOCTYPE html>
                <html lang="${lang.tag}">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>${h("title")}</title>
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            background-color: #1c1c1e;
                            color: #ffffff;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            min-height: 100vh;
                            margin: 0;
                            padding: 20px;
                        }
                        .container {
                            background-color: #2c2c2e;
                            border-radius: 12px;
                            padding: 40px;
                            box-shadow: 0 4px 6px rgba(0, 0, 0, 0.3);
                            max-width: 1200px;
                            width: 100%;
                        }
                        .main-content {
                            display: flex;
                            gap: 40px;
                            align-items: flex-start;
                        }
                        .upload-section {
                            flex: 1;
                            min-width: 450px;
                        }
                        .file-list-section {
                            flex: 1;
                            min-width: 450px;
                        }
                        h1 {
                            color: #007aff;
                            text-align: center;
                            margin-bottom: 30px;
                        }
                        .upload-area {
                            border: 2px dashed #007aff;
                            border-radius: 8px;
                            padding: 40px;
                            text-align: center;
                            transition: all 0.3s ease;
                        }
                        .upload-area:hover {
                            background-color: #3a3a3c;
                            border-color: #0051d5;
                        }
                        input[type="file"] {
                            display: none;
                        }
                        .upload-label {
                            display: inline-block;
                            padding: 12px 24px;
                            background-color: #007aff;
                            color: white;
                            border-radius: 6px;
                            cursor: pointer;
                            transition: background-color 0.3s ease;
                        }
                        .upload-label:hover {
                            background-color: #0051d5;
                        }
                        .file-list {
                            margin-top: 20px;
                            padding: 10px;
                            background-color: #3a3a3c;
                            border-radius: 6px;
                            max-height: 200px;
                            overflow-y: auto;
                        }
                        .file-item {
                            padding: 8px;
                            margin: 4px 0;
                            background-color: #48484a;
                            border-radius: 4px;
                        }
                        .submit-btn {
                            width: 100%;
                            padding: 12px;
                            margin-top: 20px;
                            background-color: #30d158;
                            color: white;
                            border: none;
                            border-radius: 6px;
                            font-size: 16px;
                            cursor: pointer;
                            transition: background-color 0.3s ease;
                        }
                        .submit-btn:hover {
                            background-color: #28a745;
                        }
                        .submit-btn:disabled {
                            background-color: #48484a;
                            cursor: not-allowed;
                        }
                        .progress-container {
                            margin-top: 20px;
                            display: none;
                        }
                        .progress-bar-bg {
                            width: 100%;
                            height: 20px;
                            background-color: #48484a;
                            border-radius: 10px;
                            overflow: hidden;
                            position: relative;
                        }
                        .progress-bar {
                            height: 100%;
                            background-color: #007aff;
                            width: 0%;
                            transition: width 0.3s ease;
                            position: relative;
                        }
                        .progress-text {
                            position: absolute;
                            top: 50%;
                            left: 50%;
                            transform: translate(-50%, -50%);
                            color: white;
                            font-size: 12px;
                            font-weight: bold;
                            z-index: 1;
                            width: 100%;
                            text-align: center;
                        }
                        .upload-status {
                            margin-top: 10px;
                            font-size: 14px;
                            color: #8e8e93;
                            text-align: center;
                        }
                        .message-container {
                            position: fixed;
                            bottom: 0;
                            left: 0;
                            right: 0;
                            z-index: 1000;
                            display: flex;
                            justify-content: center;
                            padding: 20px;
                            pointer-events: none;
                        }
                        .message {
                            padding: 20px 40px;
                            border-radius: 8px;
                            text-align: center;
                            font-weight: 500;
                            font-size: 18px;
                            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
                            animation: slideUp 0.3s ease;
                            pointer-events: auto;
                            max-width: 80%;
                        }
                        .message.fade-out {
                            animation: fadeOut 0.3s ease forwards;
                        }
                        .success {
                            background-color: #30d158;
                            color: #ffffff;
                        }
                        .error {
                            background-color: #ff3b30;
                            color: #ffffff;
                        }
                        @keyframes slideUp {
                            from {
                                opacity: 0;
                                transform: translateY(20px);
                            }
                            to {
                                opacity: 1;
                                transform: translateY(0);
                            }
                        }
                        @keyframes fadeOut {
                            from {
                                opacity: 1;
                                transform: translateY(0);
                            }
                            to {
                                opacity: 0;
                                transform: translateY(20px);
                            }
                        }
                        .file-list-container {
                            margin-top: 0;
                            padding: 0;
                            border-top: none;
                            height: 100%;
                        }
                        .file-list-section h2 {
                            color: #007aff;
                            margin-top: 0;
                            margin-bottom: 20px;
                        }
                        .file-list-header {
                            display: flex;
                            justify-content: space-between;
                            align-items: center;
                            margin-bottom: 20px;
                        }
                        .file-list-container h2 {
                            color: #007aff;
                            margin: 0;
                        }
                        .sort-controls {
                            display: flex;
                            gap: 10px;
                        }
                        .sort-btn {
                            padding: 8px 16px;
                            background-color: #48484a;
                            color: #ffffff;
                            border: none;
                            border-radius: 4px;
                            cursor: pointer;
                            font-size: 14px;
                            transition: background-color 0.3s ease;
                        }
                        .sort-btn:hover {
                            background-color: #5a5a5c;
                        }
                        .sort-btn.active {
                            background-color: #007aff;
                        }
                        .current-file-item {
                            display: flex;
                            justify-content: space-between;
                            align-items: center;
                            padding: 12px;
                            margin: 8px 0;
                            background-color: #3a3a3c;
                            border-radius: 6px;
                            transition: background-color 0.3s ease;
                        }
                        .current-file-item:hover {
                            background-color: #48484a;
                        }
                        .file-info {
                            flex: 1;
                        }
                        .file-name {
                            font-weight: bold;
                            margin-bottom: 4px;
                        }
                        .file-meta {
                            font-size: 14px;
                            color: #8e8e93;
                        }
                        .delete-btn {
                            padding: 8px 16px;
                            background-color: #ff3b30;
                            color: white;
                            border: none;
                            border-radius: 4px;
                            cursor: pointer;
                            transition: background-color 0.3s ease;
                        }
                        .delete-btn:hover {
                            background-color: #ff2d20;
                        }
                        .empty-message {
                            text-align: center;
                            color: #8e8e93;
                            padding: 40px;
                        }
                        .file-list-content {
                            max-height: 600px;
                            overflow-y: auto;
                            padding-right: 10px;
                        }
                        .file-list-content::-webkit-scrollbar {
                            width: 8px;
                        }
                        .file-list-content::-webkit-scrollbar-track {
                            background: #48484a;
                            border-radius: 4px;
                        }
                        .file-list-content::-webkit-scrollbar-thumb {
                            background: #007aff;
                            border-radius: 4px;
                        }
                        @media (max-width: 1000px) {
                            .main-content {
                                flex-direction: column;
                            }
                            .upload-section, .file-list-section {
                                min-width: auto;
                            }
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>${h("heading")}</h1>
                        <div class="main-content">
                            <div class="file-list-section">
                                <div class="file-list-container">
                                    <div class="file-list-header">
                                        <h2>${h("current_files")}</h2>
                                        <div class="sort-controls">
                                            <button class="sort-btn active" id="sortByName">${h("sort_by_name")}</button>
                                            <button class="sort-btn" id="sortByTime">${h("sort_by_time")}</button>
                                            <button class="delete-btn" id="deleteAllBtn" style="margin-left: 20px;">${h("delete_all")}</button>
                                        </div>
                                    </div>
                                    <div class="file-list-content">
                                        <div id="currentFiles"></div>
                                    </div>
                                </div>
                            </div>
                            
                            <div class="upload-section">
                                <h2>${h("upload_files")}</h2>
                                <form id="uploadForm" enctype="multipart/form-data" accept-charset="UTF-8">
                                    <div class="upload-area">
                                        <label for="fileInput" class="upload-label">
                                            ${h("choose_files")}
                                        </label>
                                        <input type="file" id="fileInput" name="files" accept=".pdf" multiple>
                                        <p>${h("or_drag")}</p>
                                    </div>
                                    <div id="fileList" class="file-list" style="display: none;"></div>
                                    <button type="submit" class="submit-btn" disabled>${h("upload")}</button>
                                    <div id="progressContainer" class="progress-container">
                                        <div class="progress-bar-bg">
                                            <div class="progress-text">0%</div>
                                            <div id="progressBar" class="progress-bar"></div>
                                        </div>
                                        <div id="uploadStatus" class="upload-status">${h("preparing")}</div>
                                    </div>
                                </form>
                            </div>
                        </div>
                    </div>
                    
                    <div id="messageContainer" class="message-container"></div>
                    
                    <script>
                        const fileInput = document.getElementById('fileInput');
                        const fileList = document.getElementById('fileList');
                        const submitBtn = document.querySelector('.submit-btn');
                        const uploadArea = document.querySelector('.upload-area');
                        const messageContainer = document.getElementById('messageContainer');
                        const form = document.getElementById('uploadForm');
                        const sortByNameBtn = document.getElementById('sortByName');
                        const sortByTimeBtn = document.getElementById('sortByTime');
                        
                        let selectedFiles = [];
                        let currentFiles = [];
                        let currentSortBy = 'name';
                        let messageTimeout = null;
                        
                        function showMessage(text, isSuccess = true) {
                            // Clear any existing timeout
                            if (messageTimeout) {
                                clearTimeout(messageTimeout);
                            }
                            
                            // Create message element
                            const messageEl = document.createElement('div');
                            messageEl.className = 'message ' + (isSuccess ? 'success' : 'error');
                            messageEl.textContent = text;
                            
                            // Clear container and add new message
                            messageContainer.innerHTML = '';
                            messageContainer.appendChild(messageEl);
                            
                            // Auto-hide after 5 seconds
                            messageTimeout = setTimeout(() => {
                                messageEl.classList.add('fade-out');
                                setTimeout(() => {
                                    messageContainer.innerHTML = '';
                                }, 300);
                            }, 5000);
                        }
                        
                        fileInput.addEventListener('change', handleFileSelect);
                        
                        sortByNameBtn.addEventListener('click', () => {
                            currentSortBy = 'name';
                            updateSortButtons();
                            displayFileList();
                        });
                        
                        sortByTimeBtn.addEventListener('click', () => {
                            currentSortBy = 'time';
                            updateSortButtons();
                            displayFileList();
                        });
                        
                        document.getElementById('deleteAllBtn').addEventListener('click', async () => {
                            if (currentFiles.length === 0) {
                                showMessage('${j("nothing_to_delete")}', false);
                                return;
                            }
                            
                            if (confirm('${j("confirm_delete_all")}')) {
                                const deleteAllBtn = document.getElementById('deleteAllBtn');
                                deleteAllBtn.disabled = true;
                                deleteAllBtn.textContent = '${j("deleting")}';
                                
                                try {
                                    const response = await fetch('/deleteAll', {
                                        method: 'DELETE'
                                    });
                                    
                                    if (response.ok) {
                                        showMessage('${j("all_deleted")}', true);
                                        loadFileList();
                                    } else {
                                        showMessage('${j("delete_all_failed")}', false);
                                    }
                                } catch (e) {
                                    showMessage('${j("delete_error")}', false);
                                } finally {
                                    deleteAllBtn.disabled = false;
                                    deleteAllBtn.textContent = '${j("delete_all")}';
                                }
                            }
                        });
                        
                        function updateSortButtons() {
                            sortByNameBtn.classList.toggle('active', currentSortBy === 'name');
                            sortByTimeBtn.classList.toggle('active', currentSortBy === 'time');
                        }
                        
                        uploadArea.addEventListener('dragover', (e) => {
                            e.preventDefault();
                            uploadArea.style.backgroundColor = '#3a3a3c';
                        });
                        
                        uploadArea.addEventListener('dragleave', () => {
                            uploadArea.style.backgroundColor = '';
                        });
                        
                        uploadArea.addEventListener('drop', (e) => {
                            e.preventDefault();
                            uploadArea.style.backgroundColor = '';
                            handleFiles(e.dataTransfer.files);
                        });
                        
                        function handleFileSelect(e) {
                            handleFiles(e.target.files);
                        }
                        
                        function handleFiles(files) {
                            selectedFiles = Array.from(files).filter(file => file.type === 'application/pdf');
                            updateFileList();
                        }
                        
                        function updateFileList() {
                            if (selectedFiles.length === 0) {
                                fileList.style.display = 'none';
                                submitBtn.disabled = true;
                                return;
                            }
                            
                            fileList.style.display = 'block';
                            fileList.innerHTML = '<h3>${jh("selected_files")}</h3>';
                            
                            selectedFiles.forEach(file => {
                                const fileItem = document.createElement('div');
                                fileItem.className = 'file-item';
                                fileItem.textContent = file.name + ' (' + formatFileSize(file.size) + ')';
                                fileList.appendChild(fileItem);
                            });
                            
                            submitBtn.disabled = false;
                        }
                        
                        function formatFileSize(bytes) {
                            if (bytes === 0) return '0 Bytes';
                            const k = 1024;
                            const sizes = ['Bytes', 'KB', 'MB', 'GB'];
                            const i = Math.floor(Math.log(bytes) / Math.log(k));
                            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
                        }
                        
                        form.addEventListener('submit', async (e) => {
                            e.preventDefault();
                            
                            if (selectedFiles.length === 0) return;
                            
                            const progressContainer = document.getElementById('progressContainer');
                            const progressBar = document.getElementById('progressBar');
                            const progressText = document.querySelector('.progress-text');
                            const uploadStatus = document.getElementById('uploadStatus');
                            
                            // Show progress container
                            progressContainer.style.display = 'block';
                            progressBar.style.width = '0%';
                            progressText.textContent = '0%';
                            uploadStatus.textContent = '${j("preparing")}';
                            
                            const formData = new FormData();
                            selectedFiles.forEach((file, index) => {
                                // Use simple index-based naming for upload
                                const uploadName = 'file_' + index + '.pdf';
                                const newFile = new File([file], uploadName, {type: file.type});
                                formData.append('files', newFile);
                                // Send original filename as Base64 encoded
                                const base64Name = btoa(unescape(encodeURIComponent(file.name)));
                                formData.append('filename_' + index, base64Name);
                            });
                            
                            submitBtn.disabled = true;
                            submitBtn.textContent = '${j("uploading")}';
                            
                            try {
                                // Create XMLHttpRequest for progress tracking
                                const xhr = new XMLHttpRequest();
                                
                                // Track upload progress
                                xhr.upload.addEventListener('progress', (e) => {
                                    if (e.lengthComputable) {
                                        const percentComplete = Math.round((e.loaded / e.total) * 100);
                                        progressBar.style.width = percentComplete + '%';
                                        progressText.textContent = percentComplete + '%';
                                        
                                        if (percentComplete === 100) {
                                            uploadStatus.textContent = '${j("processing")}';
                                        } else {
                                            const loaded = formatFileSize(e.loaded);
                                            const total = formatFileSize(e.total);
                                            uploadStatus.textContent = '${j("uploading_progress")}' + loaded + ' / ' + total;
                                        }
                                    }
                                });
                                
                                // Handle completion
                                await new Promise((resolve, reject) => {
                                    xhr.onload = () => {
                                        if (xhr.status === 200) {
                                            resolve(xhr.responseText);
                                        } else {
                                            reject(new Error(xhr.responseText));
                                        }
                                    };
                                    xhr.onerror = () => reject(new Error('${j("network_error")}'));
                                    
                                    xhr.open('POST', '/upload');
                                    xhr.send(formData);
                                });
                                
                                showMessage('${j("upload_success")}', true);
                                selectedFiles = [];
                                fileInput.value = '';
                                updateFileList();
                                
                                // Hide progress after success
                                setTimeout(() => {
                                    progressContainer.style.display = 'none';
                                }, 2000);
                                
                            } catch (error) {
                                showMessage('${j("upload_failed")}' + error.message, false);
                                progressContainer.style.display = 'none';
                            } finally {
                                submitBtn.textContent = '${j("upload")}';
                                submitBtn.disabled = false;
                                loadFileList();
                            }
                        });
                        
                        async function loadFileList() {
                            try {
                                const response = await fetch('/list');
                                currentFiles = await response.json();
                                displayFileList();
                            } catch (e) {
                                console.error('Failed to load file list:', e);
                            }
                        }
                        
                        function displayFileList() {
                            const currentFilesDiv = document.getElementById('currentFiles');
                            
                            if (currentFiles.length === 0) {
                                currentFilesDiv.innerHTML = '<div class="empty-message">${jh("no_files")}</div>';
                                return;
                            }
                            
                            // sort
                            const sortedFiles = [...currentFiles];
                            if (currentSortBy === 'name') {
                                sortedFiles.sort((a, b) => a.name.localeCompare(b.name, 'ko'));
                            } else {
                                // by time (newest first) - uses timestamp
                                sortedFiles.sort((a, b) => b.modifiedTimestamp - a.modifiedTimestamp);
                            }
                            
                            currentFilesDiv.innerHTML = sortedFiles.map(file => `
                                <div class="current-file-item">
                                    <div class="file-info">
                                        <div class="file-name">` + file.name + `</div>
                                        <div class="file-meta">` + file.size + ` • ` + file.modified + `</div>
                                    </div>
                                    <button class="delete-btn" onclick="deleteFile('` + encodeURIComponent(file.name) + `')">${th("delete")}</button>
                                </div>
                            `).join('');
                        }
                        
                        async function deleteFile(encodedFileName) {
                            if (confirm('${j("confirm_delete")}')) {
                                try {
                                    const response = await fetch('/delete/' + encodedFileName, {
                                        method: 'DELETE'
                                    });
                                    
                                    if (response.ok) {
                                        showMessage('${j("file_deleted")}', true);
                                        loadFileList();
                                    } else {
                                        showMessage('${j("delete_failed")}', false);
                                    }
                                } catch (e) {
                                    showMessage('${j("delete_error")}', false);
                                }
                            }
                        }
                        
                        // Load file list on page load
                        loadFileList();
                    </script>
                </body>
                </html>
            """.trimIndent()
    }

    internal fun escapeHtml(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** JS 작은따옴표 문자열 안. `<` 도 바꿔 `</script>` 가 생기지 않게. */
    internal fun escapeJs(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '<' -> append("\\x3C")
            else -> append(c)
        }
    }

    /** JS 템플릿 리터럴(`…`) 안. */
    internal fun escapeTemplate(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '`' -> append("\\`")
            '$' -> append("\\\$")
            else -> append(c)
        }
    }
}
