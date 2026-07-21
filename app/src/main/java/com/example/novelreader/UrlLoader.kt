package com.example.novelreader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

class UrlLoader(private val context: Context) {

    sealed class LoadResult {
        data class Success(
            val title: String,
            val content: String,
            val nextChapterUrl: String? = null
        ) : LoadResult()
        data class Error(val message: String) : LoadResult()
    }

    data class ChapterItem(val title: String, val url: String)

    sealed class ChapterListResult {
        data class Success(val items: List<ChapterItem>) : ChapterListResult()
        data class Error(val message: String) : ChapterListResult()
    }

    private data class PageData(
        val title: String,
        val content: String,
        val nextPage: String,
        val totalPages: Int?,
        val docTitle: String = "",
        val bodyLen: Int = 0,
        val isChallenge: Boolean = false
    )

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun loadUrl(url: String): LoadResult = withContext(Dispatchers.Main) {
        val webView = WebView(context.applicationContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"

        val host = Uri.parse(url).host ?: ""
        val allowedSuffix = if (host.count { it == '.' } >= 2) host.substringAfter('.') else host

        var pageFinishedCallback: (() -> Unit)? = null

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqHost = request?.url?.host ?: return null
                if (!isAllowedRequest(reqHost, host, allowedSuffix)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 忽略新建 WebView 初始的 about:blank，避免提前恢复导致提取到空页面
                if (url == null || url == "about:blank") return
                pageFinishedCallback?.invoke()
            }
        }

        val root = (context as? Activity)?.findViewById<ViewGroup>(android.R.id.content)
        attachWebView(webView, root)

        try {
            val result = withTimeout(45000) {
            suspend fun navigate(target: String) {
                withTimeout(20000) {
                    suspendCancellableCoroutine { cont ->
                        var done = false
                        pageFinishedCallback = {
                            if (!done && cont.isActive) {
                                done = true
                                pageFinishedCallback = null
                                cont.resume(Unit)
                            }
                        }
                        cont.invokeOnCancellation {
                            pageFinishedCallback = null
                            webView.stopLoading()
                        }
                        webView.loadUrl(target)
                    }
                }
            }

            suspend fun extract(): PageData = withTimeout(15000) {
                suspendCancellableCoroutine { cont ->
                    val js = """(function(){
                        function cleanText(s){
                            var r=(s||'');
                            r=r.replace(/\s*\(第.*?页\)\s*/g,'');
                            r=r.replace(/[ \t]+/g,' ');
                            r=r.replace(/\n+/g,'\n');
                            r=r.replace(/^\s+|\s+$/g,'');
                            return r;
                        }
                        function isJunk(el){
                            var t=(el.tagName||'').toLowerCase();
                            if(t==='script'||t==='style'||t==='nav'||t==='header'||t==='footer'||t==='aside'||t==='iframe'||t==='ins') return true;
                            if(t==='img'||t==='figure'||t==='video'||t==='audio') return true;
                            var c=(el.className||'').toString().toLowerCase();
                            if(/recommend|page-link|bottom-link|top-link|\bad\b|popup|modal|sidebar|comment|share|header|footer|breadcrumb|pagination|copyright/.test(c)) return true;
                            return false;
                        }
                        function isEmpty(el){
                            if(!el) return true;
                            var t=(el.textContent||'').trim();
                            if(t) return false;
                            if(el.querySelector('img,video,audio,canvas,svg')) return false;
                            return true;
                        }
                        function collectText(el){
                            if(isJunk(el)) return '';
                            var tag=(el.tagName||'').toLowerCase();
                            if(tag==='br') return '\n';
                            if(tag==='p'||tag==='div'||tag==='li'||tag==='h1'||tag==='h2'||tag==='h3'||tag==='h4'||tag==='h5'||tag==='h6'){
                                var inner='';
                                var ch=el.childNodes;
                                for(var i=0;i<ch.length;i++){
                                    var n=ch[i];
                                    if(n.nodeType===3){ var v=n.nodeValue||''; v=v.trim(); if(v) inner+=v; }
                                    else if(n.nodeType===1){ inner+=collectText(n); }
                                }
                                if(!inner.trim()) return '';
                                return inner.trim()+'\n';
                            }
                            var txt='';
                            var ch2=el.childNodes;
                            for(var j=0;j<ch2.length;j++){
                                var n2=ch2[j];
                                if(n2.nodeType===3){ var v2=n2.nodeValue||''; v2=v2.trim(); if(v2) txt+=v2+' '; }
                                else if(n2.nodeType===1){ txt+=collectText(n2); }
                            }
                            return txt.trim();
                        }
                        var cands=[
                            document.getElementById('content'),
                            document.getElementById('chaptercontent'),
                            document.getElementById('booktxt'),
                            document.getElementById('txt'),
                            document.getElementById('readcontent'),
                            document.querySelector('#content,.content,#con,.con,#booktxt,#chaptercontent,#readcontent,#booktext,.booktext,.novel-content,.read-content,.book-text,.chapter-content,#chapter_box,#acontent,.article-content,.post-content,.read-text,.txtcontent,.article,.chapter')
                        ];
                        var best=null,bestLen=0;
                        for(var i=0;i<cands.length;i++){
                            var el=cands[i]; if(!el) continue;
                            var len=collectText(el).trim().length;
                            if(len>bestLen){ bestLen=len; best=el; }
                        }
                        if(bestLen<50){
                            var all=document.getElementsByTagName('*');
                            for(var j=0;j<all.length;j++){
                                var e=all[j];
                                if(isJunk(e)) continue;
                                var len2=collectText(e).trim().length;
                                if(len2>bestLen){ bestLen=len2; best=e; }
                            }
                        }
                        var x='', t='', total=null;
                        if(best){
                            var clone=best.cloneNode(true);
                            clone.querySelectorAll('script,style,nav,header,footer,aside,a,img,figure,iframe,ins,.recommend,.page-link,.bottom-link,.top-link,.ad,.popup,.modal,.comment,.share,.breadcrumb,.pagination,.copyright').forEach(function(n){n.remove();});
                            var imgs=clone.querySelectorAll('div,p,span,strong,em,i,b,h1,h2,h3,h4,h5,h6,li,td,th,blockquote,pre');
                            for(var k=0;k<imgs.length;k++){
                                if(!imgs[k].querySelector('img') && imgs[k].textContent.trim()===''){ imgs[k].remove(); }
                            }
                            x=cleanText(collectText(clone));
                            var h=best.querySelector('h1,h2,h3,h4');
                            t=(h?h.innerText:'')||document.title||'';
                            t=cleanText(t);
                        }
                        if(!x){
                            var body=document.body?document.body.cloneNode(true):null;
                            if(body){
                                body.querySelectorAll('script,style,nav,header,footer,aside,a,img,figure,iframe,.recommend,.page-link,.bottom-link,.ad,.popup,.modal').forEach(function(n){n.remove();});
                                x=cleanText(collectText(body));
                            }
                            t=document.title||'';
                        }
                        var mm=t.match(/第\d+\/(\d+)页/);
                        if(mm) total=parseInt(mm[1],10);
                        var links=document.querySelectorAll('a');
                        var next='';
                        for(var k=0;k<links.length;k++){
                            var lt=((links[k].textContent||'')+' '+(links[k].getAttribute('title')||''));
                            var cls=(links[k].className||'').toString().toLowerCase();
                            var rel=(links[k].getAttribute('rel')||'').toLowerCase();
                            if(lt.indexOf('下一')>=0 || cls.indexOf('next')>=0 || rel==='next'){
                                var href=links[k].getAttribute('href')||'';
                                if(href && href.indexOf('javascript:')!==0){ next=href; break; }
                            }
                        }
                        if(!next && typeof nextpage !== 'undefined' && nextpage){ if((''+nextpage).indexOf('javascript:')!==0) next=nextpage; }
                        if(next){ try{ next=new URL(next, location.href).href; }catch(e){ next=''; } }
                        var dt=document.title||'';
                        var bl=document.body?document.body.innerHTML.length:0;
                        var bd=document.body?document.body.innerText:'';
                        var dtl=(dt||'').toLowerCase();
                        var bdl=(bd||'').toLowerCase();
                        var cf=0;
                        if(dtl.indexOf('just a moment')>=0 || dtl.indexOf('attention required')>=0 || bdl.indexOf('enable javascript and cookies')>=0 || bdl.indexOf('checking your browser')>=0){ cf=1; }
                        return JSON.stringify({t:t,x:x,next:next,total:total,dt:dt,bl:bl,cf:cf});
                    })()"""
                    webView.evaluateJavascript(js) { raw ->
                        if (cont.isActive) cont.resume(parsePage(raw))
                    }
                }
            }

            suspend fun waitForContent(): PageData {
                var data = extract()
                var attempt = 0
                while ((data.content.isBlank() || data.isChallenge) && attempt < 12) {
                    delay(1000)
                    data = extract()
                    attempt++
                }
                return data
            }

            navigate(url)
            delay(1500)
            val first = waitForContent()
            Log.d("UrlLoader", "page1 len=${first.content.length} next=${first.nextPage} title=[${first.title}] challenge=${first.isChallenge}")
            if (first.isChallenge && first.content.isBlank()) {
                return@withTimeout LoadResult.Error("该网站启用了 Cloudflare 人机验证，WebView 无法自动通过。请先在系统浏览器中打开该链接、通过验证后复制正文，或改用不受验证的站点。")
            }
            val originalBase = chapterBase(url)
            val sb = StringBuilder(first.content)
            var title = first.title
            var idx = 1
            var next = first.nextPage
            var total = first.totalPages
            var guard = 0
            var nextChapterUrl: String? = null

            while (next.isNotBlank() && guard < 30) {
                guard++
                if (chapterBase(next) != originalBase) {
                    nextChapterUrl = next   // 末页的"下一页"即下一章
                    break
                }
                if (total != null && idx >= total) break
                navigate(next)
                delay(1200)
                val p = waitForContent()
                Log.d("UrlLoader", "page$idx len=${p.content.length} next=${p.nextPage} dt=[${p.docTitle}] bl=${p.bodyLen}")
                if (p.content.isBlank()) break
                if (total == null && p.totalPages != null) total = p.totalPages
                if (title.isBlank()) title = p.title
                sb.append("\n\n").append(p.content)
                idx++
                next = p.nextPage
            }

            if (sb.isBlank()) {
                Log.d("UrlLoader", "result: EMPTY dt=[${first.docTitle}] bl=${first.bodyLen}")
                val hint = if (first.bodyLen > 2000) "（页面已加载但未能识别正文，可能是非常规排版）" else "（页面可能未加载/被反爬拦截）"
                LoadResult.Error("未能提取到正文$hint")
            } else {
                Log.d("UrlLoader", "merged pages=$idx len=${sb.length} title=[$title] next=[$nextChapterUrl]")
                LoadResult.Success(title.ifBlank { "未知标题" }, sb.toString().trim(), nextChapterUrl)
            }
            }
            result
        } catch (e: TimeoutCancellationException) {
            LoadResult.Error("加载超时（45秒），该站点可能启用了反爬验证或网络较慢")
        } catch (e: Exception) {
            LoadResult.Error("加载失败: ${e.message}")
        } finally {
            try { root?.removeView(webView) } catch (_: Exception) { }
            webView.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun loadChapterList(url: String): ChapterListResult = withContext(Dispatchers.Main) {
        val webView = WebView(context.applicationContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"

        val host = Uri.parse(url).host ?: ""
        val allowedSuffix = if (host.count { it == '.' } >= 2) host.substringAfter('.') else host

        var pageFinishedCallback: (() -> Unit)? = null
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqHost = request?.url?.host ?: return null
                if (!isAllowedRequest(reqHost, host, allowedSuffix)) {
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url == "about:blank") return
                pageFinishedCallback?.invoke()
            }
        }

        val root = (context as? Activity)?.findViewById<ViewGroup>(android.R.id.content)
        attachWebView(webView, root)

        return@withContext try {
            suspendCancellableCoroutine { cont ->
                pageFinishedCallback = {
                    if (cont.isActive) {
                        pageFinishedCallback = null
                        cont.resume(Unit)
                    }
                }
                cont.invokeOnCancellation {
                    pageFinishedCallback = null
                    webView.stopLoading()
                }
                webView.loadUrl(url)
            }
            delay(1500)
            val js = """(function(){
                function abs(u){ try{ return new URL(u, location.href).href; }catch(e){ return ''; } }
                function isNav(el){
                    var t=(el.tagName||'').toLowerCase();
                    if(t==='nav'||t==='header'||t==='footer'||t==='aside') return true;
                    var c=(el.className||'').toString().toLowerCase();
                    if(/nav|menu|header|footer|sidebar|aside|top-bar|bottom-bar|masthead/.test(c)) return true;
                    var id=(el.id||'').toLowerCase();
                    if(/nav|menu|header|footer|sidebar/.test(id)) return true;
                    return false;
                }
                function chapterScore(txt,href){
                    var s=0;
                    if(/第\s*\d+\s*[章回卷篇节]/i.test(txt)) s+=10;
                    if(/^\s*\d+[\.\、．\s]/.test(txt)) s+=8;
                    if(/章|节|卷|回|集|篇|序|尾声|番外|楔子|引子|后记|前言/.test(txt)) s+=4;
                    if(/^\s*\d+\s*$/.test(txt)) s+=6;
                    var p=href.toLowerCase();
                    if(/\/\d+\.html?$/.test(p)) s+=5;
                    if(/chapter|chap|read|book|novel|list|catalog|mulu/.test(p)) s+=4;
                    if(/\/\d+\/\d+/.test(p)) s+=3;
                    if(txt.length>=2 && txt.length<=30) s+=2;
                    return s;
                }
                var selectors=['#chapterList','.chapter-list','.chapterlist','#list','.list','dl.chapter-list','.box_chapter','#all-chapter','.catalog','#catalog','.mulu','#mulu','.readlist','.listmain','.list_content','#list_content','.chapter-box','.booklist','.book-list','#BookList','.booklistul','.chapters-list','#chapters-list','.volume-list','#volume-list','.section-list','#section-list','.volume','.olumes','#volumes','.booklist','#catalogList','.catalog_list','#CatalogList','.dir','#dir','dl','ol.chapter'];
                var box=null;
                for(var s=0;s<selectors.length;s++){
                    try{ box=document.querySelector(selectors[s]); if(box) break; }catch(e){}
                }
                if(!box){
                    var containers=document.querySelectorAll('div,section,ul,ol,dl');
                    var best=null,bestScore=0;
                    for(var c=0;c<containers.length;c++){
                        var el=containers[c];
                        if(isNav(el)) continue;
                        var as2=el.querySelectorAll('a');
                        if(as2.length<3) continue;
                        var total=0;
                        for(var h=0;h<as2.length;h++){
                            var hr=as2[h].getAttribute('href')||'';
                            var tx=(as2[h].textContent||'').trim();
                            total+=chapterScore(tx,hr);
                        }
                        if(total>bestScore){ bestScore=total; best=el; }
                    }
                    if(best && bestScore>=3) box=best;
                }
                var scope = box || document.body;
                var as = scope.querySelectorAll('a');
                var links = [];
                var seen={};
                for(var i=0;i<as.length;i++){
                    var a=as[i]; var href=a.getAttribute('href')||''; var txt=(a.textContent||'').trim();
                    if(!txt || txt.length>60 || txt.length<1) continue;
                    var u=abs(href); if(!u || seen[u]) continue;
                    if(u.indexOf('.jpg')>=0||u.indexOf('.png')>=0||u.indexOf('.gif')>=0) continue;
                    var sc=chapterScore(txt,href);
                    if(sc>=2){ seen[u]=1; links.push({t:txt,u:u,s:sc}); }
                }
                links.sort(function(a,b){ return b.s-a.s; });
                var out=links.map(function(l){ return {t:l.t,u:l.u}; });
                return JSON.stringify(out);
            })()"""
            val raw = suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r) }
            }
            val items = parseChapterList(raw)
            ChapterListResult.Success(items)
        } catch (e: Exception) {
            ChapterListResult.Error("目录加载失败: ${e.message}")
        } finally {
            try { root?.removeView(webView) } catch (_: Exception) { }
            webView.destroy()
        }
    }

    private fun parseChapterList(raw: String?): List<ChapterItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val inner = try {
            decodeJsonString(raw)
        } catch (_: Exception) {
            raw
        }
        val result = mutableListOf<ChapterItem>()
        return try {
            val arr = org.json.JSONArray(inner)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t", "").trim()
                val u = o.optString("u", "").trim()
                if (t.isNotBlank() && u.isNotBlank()) result.add(ChapterItem(t, u))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun chapterBase(url: String): String {
        val file = Uri.parse(url).lastPathSegment ?: return url
        return file.substringBefore('.').substringBefore('_')
    }

    private fun isAllowedRequest(reqHost: String, host: String, allowedSuffix: String): Boolean {
        if (reqHost == host) return true
        if (reqHost.endsWith(".$allowedSuffix") || reqHost.endsWith(allowedSuffix)) return true
        if (reqHost == "challenges.cloudflare.com" || reqHost.endsWith(".cloudflare.com")) return true
        if (reqHost.contains("cloudflare") ||
            reqHost.contains("hcaptcha") ||
            reqHost.contains("recaptcha") ||
            reqHost.contains("turnstile") ||
            reqHost.contains("gstatic") ||
            reqHost.contains("cdnjs")
        ) return true
        return false
    }

    private fun attachWebView(webView: WebView, root: ViewGroup?) {
        if (root == null) return
        val dm = root.resources.displayMetrics
        webView.layoutParams = ViewGroup.LayoutParams(dm.widthPixels, dm.heightPixels)
        webView.visibility = View.INVISIBLE
        webView.isClickable = false
        webView.isFocusable = false
        root.addView(webView)
    }

    private fun parsePage(raw: String?): PageData {
        if (raw.isNullOrBlank()) return PageData("", "", "", null)
        val inner = try {
            decodeJsonString(raw)
        } catch (_: Exception) {
            raw
        }
        return try {
            val obj = org.json.JSONObject(inner)
            PageData(
                obj.optString("t", ""),
                obj.optString("x", ""),
                obj.optString("next", ""),
                if (obj.isNull("total")) null else obj.optInt("total", -1).let { if (it < 0) null else it },
                obj.optString("dt", ""),
                obj.optInt("bl", 0),
                obj.optInt("cf", 0) == 1
            )
        } catch (_: Exception) {
            PageData("", inner, "", null)
        }
    }

    private fun decodeJsonString(s: String): String {
        if (s.length < 2 || !s.startsWith("\"") || !s.endsWith("\"")) return s
        val mid = s.substring(1, s.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < mid.length) {
            val ch = mid[i]
            if (ch == '\\' && i + 1 < mid.length) {
                when (mid[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(mid[i + 1])
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
