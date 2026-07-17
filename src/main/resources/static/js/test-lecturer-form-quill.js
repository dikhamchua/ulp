/* Quill helpers for the lecturer exam builder (Epic #11).
 * Owns rich-text setup, image upload, paste/drop rewrite of data:image
 * embeds, and empty-HTML checks. Form orchestration lives in
 * test-lecturer-form.js and consumes window.LfQuill.
 */
(function () {
    'use strict';

    function toast(kind, msg) {
        if (window.FcCommon) window.FcCommon.toast(kind, msg);
    }

    function isEmptyHtml(html) {
        if (!html) return true;
        // Image-only Quill payloads (e.g. <p><img src="..."></p>) are valid content.
        if (/<img\b[^>]*\bsrc\s*=/i.test(String(html))) return false;
        var text = String(html)
            .replace(/<[^>]*>/g, ' ')
            .replace(/&nbsp;/gi, ' ')
            .replace(/ /g, ' ')
            .trim();
        return text.length === 0;
    }

    function waitForQuill(cb) {
        if (window.Quill) {
            cb();
            return;
        }
        var tries = 0;
        var t = setInterval(function () {
            tries += 1;
            if (window.Quill || tries > 80) {
                clearInterval(t);
                if (window.Quill) cb();
                else toast('error', 'Không tải được trình soạn thảo. Tải lại trang.');
            }
        }, 50);
    }

    function uploadImage(file, imageUrl) {
        var fd = new FormData();
        fd.append('file', file);
        return window.FcCommon.postForm(imageUrl, fd).then(function (res) {
            if (res && res.data && res.data.url) return res.data.url;
            throw new Error('Tải ảnh thất bại');
        });
    }

    function insertImageAtCursor(quill, url) {
        var range = quill.getSelection(true);
        var index = range ? range.index : quill.getLength();
        quill.insertEmbed(index, 'image', url, 'user');
        quill.setSelection(index + 1);
    }

    function imageHandler(quill, imageUrl) {
        var input = document.createElement('input');
        input.setAttribute('type', 'file');
        input.setAttribute('accept', 'image/jpeg,image/png,image/webp');
        input.click();
        input.onchange = function () {
            var file = input.files && input.files[0];
            if (!file) return;
            uploadImage(file, imageUrl)
                .then(function (url) { insertImageAtCursor(quill, url); })
                .catch(function (err) {
                    toast('error', err.message || 'Tải ảnh thất bại');
                });
        };
    }

    function dataUrlToFile(dataUrl) {
        var parts = String(dataUrl).split(',');
        if (parts.length < 2) return null;
        var meta = parts[0];
        var mimeMatch = /data:(.*?);base64/i.exec(meta);
        var mime = (mimeMatch && mimeMatch[1]) || 'image/png';
        if (mime.indexOf('image/') !== 0) return null;
        try {
            var binary = atob(parts[1]);
            var len = binary.length;
            var bytes = new Uint8Array(len);
            for (var i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i);
            var ext = mime === 'image/jpeg' ? 'jpg' : (mime === 'image/webp' ? 'webp' : 'png');
            return new File([bytes], 'paste.' + ext, { type: mime });
        } catch (err) {
            return null;
        }
    }

    // Replace any data:image... already in the editor with uploaded /uploads/... URLs.
    function rewriteDataImages(quill, imageUrl) {
        var imgs = quill.root.querySelectorAll('img[src^="data:image"]');
        if (!imgs.length) return Promise.resolve(true);
        var chain = Promise.resolve(true);
        Array.prototype.forEach.call(imgs, function (img) {
            chain = chain.then(function () {
                var file = dataUrlToFile(img.getAttribute('src'));
                if (!file) {
                    img.remove();
                    return true;
                }
                return uploadImage(file, imageUrl).then(function (url) {
                    img.setAttribute('src', url);
                    return true;
                });
            });
        });
        return chain.catch(function (err) {
            toast('error', err.message || 'Tải ảnh thất bại');
            return false;
        });
    }

    // Paste/drop images go through the same upload endpoint (no huge data: URIs).
    function bindImagePaste(quill, hidden, imageUrl) {
        quill.root.addEventListener('paste', function (e) {
            var items = e.clipboardData && e.clipboardData.items;
            if (items) {
                for (var i = 0; i < items.length; i++) {
                    var item = items[i];
                    if (!item.type || item.type.indexOf('image/') !== 0) continue;
                    e.preventDefault();
                    e.stopPropagation();
                    var file = item.getAsFile();
                    if (!file) return;
                    uploadImage(file, imageUrl)
                        .then(function (url) { insertImageAtCursor(quill, url); })
                        .catch(function (err) {
                            toast('error', err.message || 'Tải ảnh thất bại');
                        });
                    return;
                }
            }
            // HTML paste may embed data:image; rewrite after Quill inserts it.
            setTimeout(function () {
                rewriteDataImages(quill, imageUrl).then(function (ok) {
                    if (ok) hidden.value = quill.root.innerHTML;
                });
            }, 0);
        });
    }

    function createQuill(host, hidden, placeholder, toolbar, imageUrl) {
        if (!host || !hidden || host.dataset.quillMounted === '1') return null;
        host.dataset.quillMounted = '1';
        var quill = new window.Quill(host, {
            theme: 'snow',
            placeholder: placeholder,
            modules: {
                toolbar: {
                    container: toolbar,
                    handlers: {
                        image: function () {
                            imageHandler(quill, imageUrl);
                        }
                    }
                }
            }
        });
        quill.on('text-change', function () {
            hidden.value = quill.root.innerHTML;
        });
        bindImagePaste(quill, hidden, imageUrl);
        return quill;
    }

    window.LfQuill = {
        isEmptyHtml: isEmptyHtml,
        waitForQuill: waitForQuill,
        createQuill: createQuill,
        rewriteDataImages: rewriteDataImages
    };
})();
