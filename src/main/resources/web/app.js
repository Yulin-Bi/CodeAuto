const state={sessions:[],active:null,metrics:{},evaluation:null,reflections:null,events:[],transcript:[],contextWindow:0,todoMode:'current',railView:'overview',boardMode:'session',reflectionMode:'session',expandedSessions:new Set(),treeReady:false,gitAvailable:false,rootChangedFiles:0,worktrees:[],worktreeGraph:null,pendingApprovals:[]}
const boardUi={signature:'',reflectionScrollTop:0}
let worktreeGraphState='idle'
let worktreeGraphScrollTop=0
let selectedGraphBranch=''
let worktreeUiSignature=''
const transcriptUi={session:null,signature:'',pinned:true}
const gitUi={session:null,status:null,busy:false,notice:'',error:false,previewKind:'git',memoryItems:[],previewSession:null,previewPath:null,previewStaged:false,previewCanDiff:true,previewMode:'diff',previewContent:'',previewLoading:false,previewRequest:0}
const permissionUi={current:null,busy:false}
const $=s=>document.querySelector(s)
const iconPaths={
  folder:'<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/>',
  file:'<path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5Z"/><path d="M14 2v6h6"/>',
  'arrow-up':'<path d="m5 12 7-7 7 7"/><path d="M12 19V5"/>',
  'message-plus':'<path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4Z"/><path d="M12 7v6M9 10h6"/>',
  'panel-left':'<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M9 3v18"/>',
  'panel-right':'<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M15 3v18"/>',
  files:'<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v6h6M9 13h6M9 17h4"/>',
  settings:'<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.38a2 2 0 0 0-.73-2.73l-.15-.09a2 2 0 0 1-1-1.74v-.51a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2Z"/><circle cx="12" cy="12" r="3"/>',
  key:'<circle cx="7.5" cy="15.5" r="5.5"/><path d="m21 2-9.6 9.6M15 7l2 2M18 4l2 2"/>',
  github:'<path d="M15 22v-4a4.8 4.8 0 0 0-1-3.5c3.3-.4 6.8-1.6 6.8-7A5.4 5.4 0 0 0 19.4 4 5 5 0 0 0 19.3.5S18.2.1 15 1.8a13.4 13.4 0 0 0-7 0C4.8.1 3.7.5 3.7.5A5 5 0 0 0 3.6 4a5.4 5.4 0 0 0-1.4 3.7c0 5.4 3.5 6.6 6.8 7A4.8 4.8 0 0 0 8 18v4M8 19c-3 .9-3-1.5-4-2"/>',
  activity:'<path d="M3 12h4l3-9 4 18 3-9h4"/>',
  'git-branch':'<line x1="6" x2="6" y1="3" y2="15"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M18 9a9 9 0 0 1-9 9"/>',
  trash:'<path d="M3 6h18M8 6V4h8v2m3 0-1 15H6L5 6M10 11v6M14 11v6"/>'
}
const iconSvg=name=>`<svg class="ui-icon" aria-hidden="true" viewBox="0 0 24 24">${iconPaths[name]||''}</svg>`
$('.send').innerHTML=iconSvg('arrow-up')
$('.brand-mark').remove();$('.brand span').remove();$('.brand strong').textContent='CodeAuto'
$('.view-switch').insertAdjacentHTML('beforeend','<button class="view" data-view="map">地图</button>')
$('#chat-view').insertAdjacentHTML('beforebegin','<div id="map-view" class="view-panel hidden"><div id="map-canvas" class="map-canvas"></div></div>')
$('.shell').insertAdjacentHTML('beforeend',`<aside id="right-rail" class="right-rail"><div class="rail-toolbar"><button id="toggle-right" class="rail-icon" title="收起右栏" aria-label="收起右栏">${iconSvg('panel-right')}</button><button class="rail-icon rail-tab active" data-rail-view="overview" title="运行概览" aria-label="运行概览">${iconSvg('activity')}</button><button class="rail-icon rail-tab" data-rail-view="files" title="本轮文件" aria-label="本轮文件">${iconSvg('files')}</button><button class="rail-icon rail-tab" data-rail-view="worktrees" title="Git Worktree" aria-label="Git Worktree">${iconSvg('git-branch')}</button><button class="rail-icon rail-tab" data-rail-view="config" title="模型配置" aria-label="模型配置">${iconSvg('settings')}</button><button class="rail-icon rail-tab" data-rail-view="key" title="切换 API Key" aria-label="切换 API Key">${iconSvg('key')}</button><a class="rail-icon" href="https://github.com/Yulin-Bi/CodeAuto" target="_blank" rel="noreferrer" title="打开 GitHub" aria-label="打开 GitHub">${iconSvg('github')}</a></div><div class="rail-panels"><div id="rail-overview" class="rail-view"><div class="rail-title">Token 用量</div><div id="rail-token" class="rail-card token-card"><div class="token-numbers"><b>0</b><span>/ 0 tokens</span></div><div class="token-track"><div id="token-fill" class="token-fill"></div></div><div id="token-caption" class="token-caption">等待上下文统计</div></div><div class="rail-section-head"><div class="rail-title">Todo</div><div class="todo-tabs"><button class="todo-tab active" data-todo-view="current">当前</button><button class="todo-tab" data-todo-view="history">历史</button></div></div><div id="rail-todo" class="rail-card todo-list">加载中…</div><div class="rail-title">本轮状态</div><div id="rail-eval" class="rail-card">事件、工具和错误会持续记录</div><button id="export-trace" class="rail-action">导出当前 Trace</button></div><div id="rail-files" class="rail-view hidden"><div class="rail-view-head"><b>本轮文件</b><span>选中会话的执行目录</span></div><div id="rail-files-list" class="file-list"></div><pre id="file-preview" class="file-preview hidden"></pre></div><div id="rail-worktrees" class="rail-view hidden"><div class="rail-view-head"><b>Git Worktree</b><span>会话与代码分支</span></div><div id="rail-worktree-current"></div><div class="rail-title worktree-list-title">仓库工作区</div><div id="rail-worktree-list" class="worktree-list"></div></div><div id="rail-config" class="rail-view hidden"><div class="rail-view-head"><b>模型配置</b><span>保存后下一轮生效</span></div><form id="config-form" class="rail-form"><label>模型<input id="config-model" required></label><label>Base URL<input id="config-base-url" placeholder="https://api.example.com"></label><div class="rail-form-grid"><label>上下文窗口<input id="config-context" type="number" min="1"></label><label>最大输出<input id="config-output" type="number" min="1"></label></div><label class="check-label"><input id="config-strip-thinking" type="checkbox">请求时移除 thinking</label><button type="submit" class="rail-primary">保存配置</button><div id="config-status" class="form-status"></div></form></div><div id="rail-key" class="rail-view hidden"><div class="rail-view-head"><b>API Key</b><span id="key-hint">尚未配置</span></div><form id="key-form" class="rail-form"><label>新的 Key<input id="key-value" type="password" autocomplete="new-password" placeholder="输入后将覆盖当前 Key"></label><button type="submit" class="rail-primary">保存并切换</button><button id="clear-key" type="button" class="rail-secondary">清除 Key</button><div id="key-status" class="form-status"></div></form></div></div></aside>`)
$('#rail-worktree-current').insertAdjacentHTML('afterend','<section id="git-manager" class="git-manager"><div class="rail-empty">选择会话后管理 Git 状态</div></section>')
$('.worktree-list-title').textContent='分支轨迹'
document.body.insertAdjacentHTML('beforeend',`<dialog id="fork-dialog" class="fork-dialog"><form id="fork-form"><div class="fork-kicker">创建会话分支</div><h2 id="fork-heading">选择代码工作区</h2><p class="fork-intro">对话上下文总会继承；代码目录可以共享，也可以使用独立 Git Worktree。</p><input id="fork-parent" type="hidden"><div class="fork-fields"><label>会话名称<input id="fork-title" maxlength="80" required></label><label>Git 分支名称<div class="branch-input"><span>codeauto/</span><input id="fork-branch" maxlength="80" placeholder="自动生成"></div></label></div><label class="fork-choice"><input type="radio" name="fork-mode" value="isolated" checked><span class="fork-choice-icon">${iconSvg('git-branch')}</span><span><b>隔离工作区 <em>推荐</em></b><small>创建独立 Git 分支与 Worktree，适合并行修改代码。</small></span></label><label class="fork-choice"><input type="radio" name="fork-mode" value="shared"><span class="fork-choice-icon">${iconSvg('folder')}</span><span><b>共享当前工作区</b><small>只分叉对话上下文，不创建新的代码目录。</small></span></label><div id="fork-warning" class="fork-warning hidden"></div><div id="fork-error" class="fork-error"></div><div class="fork-actions"><button id="fork-cancel" type="button" class="rail-secondary">取消</button><button id="fork-submit" type="submit" class="rail-primary">创建分支</button></div></form></dialog><dialog id="delete-dialog" class="fork-dialog delete-dialog"><form id="delete-form"><div class="fork-kicker danger">删除会话</div><h2 id="delete-heading">确认删除</h2><p id="delete-description" class="fork-intro"></p><input id="delete-session-id" type="hidden"><label id="delete-force-row" class="delete-force hidden"><input id="delete-force" type="checkbox"><span><b>强制删除代码分支</b><small>将丢弃未提交修改或尚未合并的提交，此操作无法撤销。</small></span></label><div id="delete-error" class="fork-error"></div><div class="fork-actions"><button id="delete-cancel" type="button" class="rail-secondary">取消</button><button id="delete-submit" type="submit" class="danger-button">删除</button></div></form></dialog>`)
document.body.insertAdjacentHTML('beforeend',`<dialog id="git-file-dialog" class="git-file-dialog"><div class="git-file-shell"><header class="git-file-header"><div class="git-file-identity"><span>${iconSvg('file')}</span><div><b id="git-file-name">文件</b><small id="git-file-context">当前工作区</small></div></div><div class="git-file-actions"><button id="git-file-copy" type="button">复制</button><button id="git-file-close" type="button" class="git-file-close" aria-label="关闭">×</button></div></header><nav class="git-file-tabs" aria-label="文件查看方式"><button class="active" data-git-preview="diff">Diff</button><button data-git-preview="file">完整文件</button></nav><main id="git-file-content" class="git-file-content"><div class="git-file-loading">正在读取文件…</div></main><footer class="git-file-footer"><span id="git-file-footnote">只读预览</span><span>Esc 关闭</span></footer></div></dialog>`)
document.body.insertAdjacentHTML('beforeend',`<dialog id="permission-dialog" class="permission-dialog"><div class="permission-shell"><div class="permission-kicker"><span class="permission-pulse"></span><span>等待你的批准</span></div><h2 id="permission-title">Agent 请求执行操作</h2><p id="permission-summary"></p><div class="permission-scope"><span id="permission-kind">操作</span><code id="permission-scope"></code></div><label class="permission-auto"><span><b>本轮自动权限</b><small>仅对当前这一轮任务生效，任务结束后自动恢复询问。</small></span><select id="permission-auto-policy"><option value="ASK">每次询问</option><option value="EDITS">自动允许后续文件修改</option><option value="ALL">自动允许本轮所有请求</option></select></label><label class="permission-feedback"><span>拒绝原因（可选）</span><textarea id="permission-feedback" placeholder="告诉 Agent 应该如何调整"></textarea></label><div id="permission-error" class="fork-error"></div><div class="permission-actions"><button type="button" class="permission-deny" data-permission-action="deny">拒绝</button><button type="button" class="permission-secondary" data-permission-decision="ALLOW_ONCE">允许一次</button><button type="button" class="permission-secondary" data-permission-decision="ALLOW_TURN">本轮允许</button><button type="button" class="permission-primary" data-permission-decision="ALLOW_ALWAYS">始终允许</button></div></div></dialog>`)
const sessionTitleElement=$('#session-title'), titleLine=document.createElement('div');titleLine.className='title-line';sessionTitleElement.parentNode.insertBefore(titleLine,sessionTitleElement);titleLine.appendChild(sessionTitleElement);titleLine.insertAdjacentHTML('beforeend',`<button id="rename-session" class="rename-session" title="修改会话标题" aria-label="修改会话标题">✎</button><button id="delete-session" class="rename-session delete-session" title="删除会话" aria-label="删除会话">${iconSvg('trash')}</button><input id="rename-input" class="rename-input hidden" maxlength="80" aria-label="会话标题">`)
const sidebar=$('.sidebar'),workspaceBox=$('#workspace'),workspaceTitle=workspaceBox.previousElementSibling,metricsBox=$('#metrics'),metricsTitle=metricsBox.previousElementSibling,mapTitle=document.querySelector('.map-title'),newSessionButton=$('#new-session');workspaceTitle.remove();workspaceBox.remove();metricsTitle.remove();metricsBox.remove();mapTitle.textContent='会话';mapTitle.classList.remove('map-title');newSessionButton.className='left-new-session';newSessionButton.innerHTML=`${iconSvg('message-plus')}<span>新建对话</span>`;const sidebarActions=document.createElement('div');sidebarActions.className='sidebar-actions';sidebar.insertBefore(sidebarActions,mapTitle);sidebarActions.appendChild(newSessionButton);sidebarActions.insertAdjacentHTML('beforeend',`<button id="toggle-left" class="side-collapse" title="收起左栏" aria-label="收起左栏">${iconSvg('panel-left')}</button>`)
async function api(path,options={}){const r=await fetch(path,{headers:{'content-type':'application/json',...(options.headers||{})},...options});const b=await r.json();if(!r.ok)throw Error(b.error||'请求失败');return b}
const shell=$('.shell')
let leftCollapsed=false,rightCollapsed=false
try{leftCollapsed=localStorage.getItem('codeauto.leftCollapsed')==='1';rightCollapsed=localStorage.getItem('codeauto.rightCollapsed')==='1'}catch{}
function applyShellState(){shell.classList.toggle('left-collapsed',leftCollapsed);shell.classList.toggle('right-collapsed',rightCollapsed);$('#toggle-left').title=leftCollapsed?'展开左栏':'收起左栏';$('#toggle-right').title=rightCollapsed?'展开右栏':'收起右栏'}
function saveShellState(){try{localStorage.setItem('codeauto.leftCollapsed',leftCollapsed?'1':'0');localStorage.setItem('codeauto.rightCollapsed',rightCollapsed?'1':'0')}catch{}}
function setRailView(view){state.railView=view;rightCollapsed=false;document.querySelectorAll('.rail-tab').forEach(button=>button.classList.toggle('active',button.dataset.railView===view));document.querySelectorAll('.rail-view').forEach(panel=>panel.classList.toggle('hidden',panel.id!==`rail-${view}`));applyShellState();saveShellState();if(view==='files')loadFiles();if(view==='worktrees'){loadWorktreeGraph();loadGitStatus()}if(view==='config'||view==='key')loadSettings()}
async function loadWorktreeGraph(){worktreeGraphState='loading';if(state.railView==='worktrees')renderWorktrees();try{state.worktreeGraph=await api('/api/worktrees/graph');worktreeGraphState='ready';if(state.railView==='worktrees')renderWorktrees()}catch(error){state.worktreeGraph=null;worktreeGraphState='error';if(state.railView==='worktrees')renderWorktrees()}}
async function loadSettings(){try{const settings=await api('/api/settings');$('#config-model').value=settings.model||'';$('#config-base-url').value=settings.baseUrl||'';$('#config-context').value=settings.contextWindow||'';$('#config-output').value=settings.maxOutputTokens||'';$('#config-strip-thinking').checked=!!settings.stripThinking;$('#key-hint').textContent=settings.authConfigured?`当前 ${settings.authHint}`:'尚未配置'}catch(error){$('#config-status').textContent=error.message;$('#key-status').textContent=error.message}}
async function saveConfig(event){event.preventDefault();const status=$('#config-status');status.textContent='保存中…';try{await api('/api/settings',{method:'POST',body:JSON.stringify({model:$('#config-model').value.trim(),baseUrl:$('#config-base-url').value.trim(),contextWindow:Number($('#config-context').value),maxOutputTokens:Number($('#config-output').value),stripThinking:$('#config-strip-thinking').checked})});status.textContent='配置已保存，下一轮立即使用';await refresh()}catch(error){status.textContent=error.message}}
async function saveKey(clear=false){const status=$('#key-status'),value=$('#key-value').value.trim();if(!clear&&!value){status.textContent='请输入新的 Key';return}status.textContent='保存中…';try{const settings=await api('/api/settings',{method:'POST',body:JSON.stringify(clear?{clearAuthToken:true}:{authToken:value})});$('#key-value').value='';$('#key-hint').textContent=settings.authConfigured?`当前 ${settings.authHint}`:'尚未配置';status.textContent=clear?'Key 已清除':'Key 已切换，下一轮立即使用'}catch(error){status.textContent=error.message}}
async function loadFiles(){const list=$('#rail-files-list'),preview=$('#file-preview');preview.classList.add('hidden');if(!state.active){list.innerHTML='<div class="rail-empty">选择会话后查看文件</div>';return}list.innerHTML='<div class="rail-empty">正在读取…</div>';try{const files=await api(`/api/sessions/${state.active}/files`);list.innerHTML=files.length?files.map(file=>`<button class="file-row" data-file-path="${esc(file.path)}"><span>${iconSvg('file')}</span><span class="file-copy"><b title="${esc(file.path)}">${esc(file.path.split('/').pop())}</b><small>${esc(file.operation)}${file.exists?` · ${formatBytes(file.size)}`:' · 文件不存在'}</small></span></button>`).join(''):'<div class="rail-empty">本轮尚未修改或生成文件</div>';document.querySelectorAll('.file-row').forEach(row=>row.onclick=()=>previewFile(row.dataset.filePath))}catch(error){list.innerHTML=`<div class="rail-empty rail-error">${esc(error.message)}</div>`}}
function previewFile(path){gitUi.previewKind='git';gitUi.previewSession=state.active;gitUi.previewPath=path;gitUi.previewStaged=false;gitUi.previewCanDiff=true;gitUi.previewMode='file';gitUi.previewContent='';resetPreviewTabs();$('#git-file-dialog').showModal();loadGitPreview('file')}
function resetPreviewTabs(){document.querySelector('[data-git-preview="diff"]').textContent='Diff';document.querySelector('[data-git-preview="file"]').textContent='完整文件'}
function openMemoryPreview(item,type){gitUi.previewKind='memory';const reflection=type==='bullet'?(gitUi.memoryItems.find(x=>x.memoryType==='reflection'&&x.pairedBullet?.id===item.id)||item):item,paired=reflection.pairedBullet||null,selected=type==='bullet'&&paired?paired:reflection;gitUi.memoryItems=[{...reflection,memoryType:'reflection'},...(paired?[{...paired,memoryType:'bullet'}]:[])];gitUi.previewPath=selected.title||'未命名';gitUi.previewContent=selected.content||'';gitUi.previewRenderedHtml=selected.renderedHtml||'';gitUi.previewCanDiff=false;gitUi.previewMode=type;const diff=document.querySelector('[data-git-preview="diff"]'),file=document.querySelector('[data-git-preview="file"]');diff.textContent='反思';file.textContent='Bullet';diff.disabled=!paired;file.disabled=!paired;diff.classList.toggle('active',type==='reflection');file.classList.toggle('active',type==='bullet');const dialog=$('#git-file-dialog');if(!dialog.open)dialog.showModal();renderGitFileDialog()}
function formatBytes(size){if(size<1024)return `${size} B`;if(size<1024*1024)return `${(size/1024).toFixed(1)} KB`;return `${(size/1024/1024).toFixed(1)} MB`}
applyShellState()
function esc(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
const branchPalette=['#f26b38','#df3e7b','#0f9f9a','#2f9d61','#c98913','#d84d58','#0891b2','#64748b']
const branchColors=new Map()
function assignBranchColors(branches=[]){const names=[...new Set(branches.map(String))].sort((a,b)=>a.localeCompare(b));let next=0;for(const name of names){const clean=name.replace(/^refs\/heads\//,'');if(clean==='main'||clean==='master')branchColors.set(name,'#2878d0');else if(clean==='develop'||clean.endsWith('/develop'))branchColors.set(name,'#8b5cf6');else if(!branchColors.has(name))branchColors.set(name,branchPalette[next++%branchPalette.length])}}
function branchColor(branch){const name=String(branch||'main').replace(/^refs\/heads\//,'');if(name==='main'||name==='master')return '#2878d0';if(name==='develop'||name.endsWith('/develop'))return '#8b5cf6';if(!branchColors.has(name))assignBranchColors([name]);return branchColors.get(name)||branchPalette[0]}
function sessionTitle(id){return state.sessions.find(x=>x.id===id)?.title||'原会话'}
function sessionChildren(id){return state.sessions.filter(x=>x.parentSessionId===id)}
function ensureActivePathExpanded(){let current=state.sessions.find(x=>x.id===state.active),guard=0;while(current?.parentSessionId&&guard++<64){state.expandedSessions.add(current.parentSessionId);current=state.sessions.find(x=>x.id===current.parentSessionId)}}
function sessionTreeHtml(){
  const roots=state.sessions.filter(x=>!x.parentSessionId||!state.sessions.some(parent=>parent.id===x.parentSessionId))
  const renderNode=(session,depth,seen)=>{if(seen.has(session.id))return '';const nextSeen=new Set(seen);nextSeen.add(session.id);const children=sessionChildren(session.id),expanded=state.expandedSessions.has(session.id),hasChildren=children.length>0;return `<div class="session-tree-node"><div class="session-tree-row ${session.id===state.active?'active':''}" data-id="${esc(session.id)}" style="--tree-depth:${depth}"><button class="tree-toggle ${hasChildren?'':'empty'}" data-tree-id="${esc(session.id)}" aria-label="${expanded?'收起':'展开'}分支">${hasChildren?(expanded?'▾':'▸'):'•'}</button><span class="tree-folder ${hasChildren?'has-children':'leaf'} ${expanded?'open':''}" aria-hidden="true">${iconSvg(hasChildren?'folder':'file')}</span><span class="tree-copy"><b>${esc(session.title||'未命名会话')}</b><small>${session.messageCount} 条消息${hasChildren?` · ${children.length} 个分支`:''}</small></span></div>${hasChildren&&expanded?`<div class="session-tree-children">${children.map(child=>renderNode(child,depth+1,nextSeen)).join('')}</div>`:''}</div>`}
  return roots.map(root=>renderNode(root,0,new Set())).join('')||'<div class="muted" style="padding:10px">点击右上角创建会话</div>'
}
function sessionDepth(session,seen=new Set()){
  if(!session?.parentSessionId||seen.has(session.id))return 0
  seen.add(session.id)
  return 1+sessionDepth(state.sessions.find(x=>x.id===session.parentSessionId),seen)
}
function selectedFamily(){
  let root=state.sessions.find(x=>x.id===state.active)
  if(!root)return []
  const seen=new Set()
  while(root.parentSessionId&&!seen.has(root.id)){
    seen.add(root.id)
    const parent=state.sessions.find(x=>x.id===root.parentSessionId)
    if(!parent)break
    root=parent
  }
  const family=[],queue=[root],included=new Set()
  while(queue.length){const current=queue.shift();if(!current||included.has(current.id))continue;included.add(current.id);family.push(current);queue.push(...sessionChildren(current.id))}
  return family
}
function mapHtml(){
  const family=selectedFamily()
  if(!family.length)return '<div class="empty">选择会话后，这里会显示它的分支地图</div>'
  const lanes=new Map()
  family.forEach(session=>{const depth=sessionDepth(session);if(!lanes.has(depth))lanes.set(depth,[]);lanes.get(depth).push(session)})
  const pathFor=session=>{const path=[],seen=new Set();let current=session;while(current&&!seen.has(current.id)){seen.add(current.id);path.unshift(current.title||'未命名会话');current=state.sessions.find(x=>x.id===current.parentSessionId)}return path.join(' / ')}
  return `<svg id="map-links" class="map-links" aria-hidden="true"></svg>${[...lanes.entries()].sort((a,b)=>a[0]-b[0]).map(([depth,sessions])=>`<div class="map-lane"><div class="map-lane-label">${depth===0?'主会话':`分支层级 ${depth}`}</div>${sessions.map(x=>`<div class="map-node ${x.id===state.active?'active':''}" data-map-id="${esc(x.id)}"><b>${esc(x.title||'未命名会话')}</b><small class="map-path" title="${esc(pathFor(x))}">${esc(pathFor(x))}</small><span>${x.messageCount} 条消息</span><div class="map-badges">${x.worktreePath?`<i class="worktree-badge ${x.worktreeAvailable?'':'missing'}" style="--branch-color:${branchColor(x.gitBranch)}">${iconSvg('git-branch')}${esc(x.gitBranch||'Worktree')}</i>`:''}</div><div class="map-actions"><button class="map-fork" data-fork-id="${esc(x.id)}">＋ 创建分支</button><button class="map-delete" data-delete-id="${esc(x.id)}">删除</button></div></div>`).join('')}</div>`).join('')}`
}
function drawMapLinks(){
  const canvas=$('#map-canvas'),svg=$('#map-links');if(!canvas||!svg||$('#map-view').classList.contains('hidden'))return
  const canvasRect=canvas.getBoundingClientRect();svg.setAttribute('width',canvas.scrollWidth);svg.setAttribute('height',canvas.scrollHeight)
  svg.innerHTML=selectedFamily().filter(x=>x.parentSessionId).map(child=>{const from=canvas.querySelector(`[data-map-id="${CSS.escape(child.parentSessionId)}"]`),to=canvas.querySelector(`[data-map-id="${CSS.escape(child.id)}"]`);if(!from||!to)return '';const a=from.getBoundingClientRect(),b=to.getBoundingClientRect(),x1=a.right-canvasRect.left+canvas.scrollLeft,y1=a.top+a.height/2-canvasRect.top+canvas.scrollTop,x2=b.left-canvasRect.left+canvas.scrollLeft,y2=b.top+b.height/2-canvasRect.top+canvas.scrollTop,curve=Math.max(34,(x2-x1)*.48);return `<path d="M ${x1} ${y1} C ${x1+curve} ${y1}, ${x2-curve} ${y2}, ${x2} ${y2}"/>`}).join('')
}
function queueMapLinks(){requestAnimationFrame(()=>requestAnimationFrame(drawMapLinks))}
function render(){
  $('#session-map').innerHTML=sessionTreeHtml()
  document.querySelectorAll('.session-tree-row').forEach(row=>row.onclick=e=>{if(e.target.closest('.tree-toggle'))return;const hasChildren=sessionChildren(row.dataset.id).length>0;if(hasChildren)state.expandedSessions.add(row.dataset.id);selectSession(row.dataset.id)})
  document.querySelectorAll('.tree-toggle:not(.empty)').forEach(btn=>btn.onclick=e=>{e.stopPropagation();state.expandedSessions.has(btn.dataset.treeId)?state.expandedSessions.delete(btn.dataset.treeId):state.expandedSessions.add(btn.dataset.treeId);render()})
  const m=state.metrics||{}
  const s=state.sessions.find(x=>x.id===state.active)
  $('#session-title').textContent=s?(s.title||'未命名会话'):'选择一个会话'
  $('#rename-session').disabled=!s
  $('#delete-session').disabled=!s
  const waitingApproval=(state.pendingApprovals||[]).some(item=>item.sessionId===state.active),running=!!s?.running
  $('#session-meta').textContent=waitingApproval?'等待权限审批…':running?'Agent 正在工作…':'';$('#session-meta').classList.toggle('hidden',!running)
  $('#connection').textContent=waitingApproval?'等待审批':running?'Agent 工作中':'实时连接';$('#connection').classList.toggle('busy',running)
  const prompt=$('#prompt'),send=$('.send');prompt.disabled=!s||running;prompt.placeholder=running?'Agent 正在执行当前任务…':'描述你想完成的事情…（Enter 发送，Shift+Enter 换行）';send.disabled=!s||running;send.innerHTML=running?'<span class="send-spinner" aria-label="任务执行中"></span>':iconSvg('arrow-up');send.title=running?'任务执行中':'发送消息';$('#composer').classList.toggle('running',running)
  const used=s?.contextTokens||0,limit=state.contextWindow||0,percent=limit?Math.min(100,Math.round(used/limit*100)):0
  $('#rail-token').querySelector('.token-numbers').innerHTML=`<b>${used.toLocaleString()}</b><span>/ ${limit.toLocaleString()} tokens</span>`
  $('#token-fill').style.width=`${percent}%`;$('#token-fill').classList.toggle('warning',percent>=70);$('#token-fill').classList.toggle('danger',percent>=90)
  $('#token-caption').textContent=s?(used?`已使用 ${percent}% · 剩余约 ${Math.max(0,limit-used).toLocaleString()}`:'本轮尚未产生 Token 统计'):'选择会话后显示实时用量'
  $('#rail-eval').innerHTML=s?`${s.running?'Agent 正在运行':'Agent 已就绪'}<br>压缩 ${s.compactions} 次 · 错误 ${s.errors} 次`:'事件、工具和错误会持续记录'
  renderTodos()
  if(state.railView==='worktrees')renderWorktrees()
  renderTranscript(s)
  $('#timeline').innerHTML=state.events.slice().reverse().map(e=>`<div class="event"><div class="event-type">${esc(e.type)} <span class="muted">· ${new Date(e.time).toLocaleTimeString()}</span></div><pre>${esc(JSON.stringify(e.payload,null,2))}</pre></div>`).join('')||'<div class="empty">本轮还没有事件</div>'
  $('#map-canvas').innerHTML=mapHtml()
  document.querySelectorAll('.map-node').forEach(x=>x.onclick=e=>{if(!e.target.closest('.map-actions'))selectSession(x.dataset.mapId)})
  document.querySelectorAll('.map-fork').forEach(x=>x.onclick=e=>{e.stopPropagation();openForkDialog(x.dataset.forkId)})
  document.querySelectorAll('.map-delete').forEach(x=>x.onclick=e=>{e.stopPropagation();openDeleteDialog(x.dataset.deleteId)})
  queueMapLinks()
  renderBoard()
  renderPermissionApproval()
}
function openForkDialog(parentId){
  const parent=state.sessions.find(session=>session.id===parentId),dialog=$('#fork-dialog')
  $('#fork-parent').value=parentId;$('#fork-heading').textContent=`从「${parent?.title||'当前会话'}」创建分支`;$('#fork-error').textContent=''
  $('#fork-title').value=`${parent?.title||'会话'} · 分支`;$('#fork-branch').value=''
  const isolated=dialog.querySelector('input[value="isolated"]');isolated.disabled=!state.gitAvailable
  dialog.querySelector(`input[value="${state.gitAvailable?'isolated':'shared'}"]`).checked=true
  const warning=$('#fork-warning')
  if(!state.gitAvailable){warning.textContent='当前目录不是 Git 仓库，只能创建共享工作区的会话分支。';warning.classList.remove('hidden')}
  else if(state.rootChangedFiles>0&&!parent?.worktreePath){warning.textContent=`主工作区有 ${state.rootChangedFiles} 个未提交修改。新 Worktree 基于当前 HEAD，不会自动复制这些修改。`;warning.classList.remove('hidden')}
  else warning.classList.add('hidden')
  updateForkMode()
  dialog.showModal()
}
function updateForkMode(){const isolated=$('#fork-dialog').querySelector('input[name="fork-mode"]:checked')?.value==='isolated';$('#fork-branch').disabled=!isolated;$('#fork-branch').closest('label').classList.toggle('disabled',!isolated)}
async function submitFork(event){
  event.preventDefault();const dialog=$('#fork-dialog'),submit=$('#fork-submit'),parentId=$('#fork-parent').value,isolated=dialog.querySelector('input[name="fork-mode"]:checked')?.value==='isolated'
  submit.disabled=true;submit.textContent=isolated?'正在创建 Worktree…':'正在创建分支…';$('#fork-error').textContent=''
  try{const body=await api(`/api/sessions/${parentId}/fork`,{method:'POST',body:JSON.stringify({isolated,title:$('#fork-title').value.trim(),branchName:$('#fork-branch').value.trim()})});state.active=body.active||state.active;dialog.close();ensureActivePathExpanded();await refresh();if(isolated)setRailView('worktrees')}
  catch(error){$('#fork-error').textContent=error.message}
  finally{submit.disabled=false;submit.textContent='创建分支'}
}
function graphCommitsFor(worktree){const graph=state.worktreeGraph,nodes=new Map((graph?.nodes||[]).map(node=>[node.hash,node])),head=graph?.branches?.[worktree.branch]||worktree.head,commits=[],seen=new Set(),walk=head;while(walk&&nodes.has(walk)&&!seen.has(walk)&&commits.length<12){const node=nodes.get(walk);seen.add(walk);commits.push(node);walk=(node.parents||[])[0]}return commits.length?commits:(worktree.recentCommits||[]).slice(0,12)}
function renderVerticalWorktreeGraph(){
  const graph=state.worktreeGraph||{},nodes=graph.nodes||[],branches=Object.entries(graph.branches||{}).sort(([a],[b])=>{const rank=name=>name==='main'||name==='master'?0:name==='develop'||name.endsWith('/develop')?1:2;return rank(a)-rank(b)||a.localeCompare(b)}),laneByHash=new Map();
  assignBranchColors(branches.map(([branch])=>branch));
  branches.forEach(([branch,head],lane)=>laneByHash.set(head,lane));branches.forEach(([branch,head],lane)=>{let hash=head,guard=0;while(hash&&guard++<nodes.length){if(!laneByHash.has(hash))laneByHash.set(hash,lane);const node=nodes.find(item=>item.hash===hash);hash=node?.parents?.[0]||''}});
  const visible=nodes.slice(0,100),index=new Map(visible.map((node,i)=>[node.hash,i])),laneCount=Math.max(1,branches.length),laneGap=21,width=Math.max(240,72+laneCount*laneGap),height=Math.max(160,visible.length*34+35),x=lane=>24+lane*laneGap,y=i=>22+i*34,laneBranches=branches.map(([branch])=>branch),selectedLane=laneBranches.indexOf(selectedGraphBranch);
  const pushed=new Set(),remoteHeads=Object.values(graph.remoteBranches||{});const nodeByHash=new Map(nodes.map(node=>[node.hash,node]));const visit=(hash)=>{if(!hash||pushed.has(hash)||!nodeByHash.has(hash))return;pushed.add(hash);(nodeByHash.get(hash).parents||[]).forEach(visit)};remoteHeads.forEach(visit);
  const edgeSvg=(graph.edges||[]).filter(edge=>index.has(edge.child)&&index.has(edge.parent)).map(edge=>{const from=index.get(edge.child),to=index.get(edge.parent),a=laneByHash.get(edge.child)||0,b=laneByHash.get(edge.parent)||a,mid=x(a)+(x(b)-x(a))/2;return `<path class="graph-edge ${a===b?'straight':'merge'}" style="--graph-color:${branchColor(laneBranches?.[a]||'main')}" d="M ${x(a)} ${y(from)} L ${mid} ${y(from)} L ${mid} ${y(to)} L ${x(b)} ${y(to)}"/>`}).join('');
  const nodeSvg=visible.map((node,i)=>{const lane=laneByHash.get(node.hash)||0,branch=laneBranches[lane],color=branchColor(branch),active=selectedLane<0||lane===selectedLane,remote=pushed.has(node.hash);return `<g class="graph-commit ${active?'':'dimmed'} ${remote?'pushed':'local'}" style="--graph-color:${color}" tabindex="0" role="button" aria-label="${remote?'已推送':'仅本地'}提交：${esc(node.subject||'(无提交信息)')}" data-graph-branch="${esc(branch)}" data-commit-detail="${esc(node.subject||'(无提交信息)')} · ${esc(node.author||'未知作者')} · ${esc(node.hash.slice(0,8))}"><circle cx="${x(lane)}" cy="${y(i)}" r="4"/>${remote?'':'<circle class="graph-local-marker" cx="'+x(lane)+'" cy="'+y(i)+'" r="1.7"/>'}<text x="${x(lane)+11}" y="${y(i)+4}">${esc(node.subject||'(无提交信息)')}</text></g>`}).join('');
  const laneRails=laneBranches.map((branch,lane)=>`<line class="graph-lane-rail" style="--graph-color:${branchColor(branch)}" x1="${x(lane)}" y1="8" x2="${x(lane)}" y2="${height-8}"/>`).join('');
  return `<div class="worktree-graph-shell"><div class="worktree-graph-caption"><b>Commit Graph</b><span>${selectedGraphBranch?`当前分支：${esc(selectedGraphBranch)}`:`${visible.length} 个提交 · ${branches.length} 个分支`}</span></div><div class="worktree-graph-scroll"><svg class="worktree-graph-svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" aria-label="竖向 Git 分支图">${laneRails}${edgeSvg}${nodeSvg}</svg></div></div>`
}
function bindGraphNodes(){document.querySelectorAll('[data-commit-detail]').forEach(button=>{button.onclick=()=>{selectedGraphBranch=button.dataset.graphBranch||selectedGraphBranch;refreshGraphOnly()};button.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();button.click()}}})}
function refreshGraphOnly(){const host=$('#rail-worktree-list'),scroll=$('.worktree-graph-scroll'),top=scroll?.scrollTop||0;if(!host||!state.worktreeGraph?.nodes?.length)return;host.innerHTML=renderVerticalWorktreeGraph();bindGraphNodes();requestAnimationFrame(()=>{const next=$('.worktree-graph-scroll');if(next)next.scrollTop=top})}
function renderWorktrees(){
  const rail=$('#right-rail'),railTop=rail?.scrollTop||0,graphScroll=$('.worktree-graph-scroll'),graphTop=graphScroll?graphScroll.scrollTop:worktreeGraphScrollTop;worktreeGraphScrollTop=graphTop;const current=$('#rail-worktree-current'),list=$('#rail-worktree-list'),session=state.sessions.find(item=>item.id===state.active)
  const signature=JSON.stringify([state.active,state.worktreeGraph,state.worktrees,gitUi.status,selectedGraphBranch]);if(signature===worktreeUiSignature)return;worktreeUiSignature=signature
  if(!state.gitAvailable){current.innerHTML='<div class="rail-card worktree-empty">当前工作区不是 Git 仓库</div>';list.innerHTML='';return}
  current.innerHTML=session?.worktreePath?`<div class="rail-card worktree-current"><div class="worktree-status"><span class="status-dot ${session.worktreeAvailable?'ok':'missing'}"></span><b>${session.worktreeAvailable?'已绑定隔离工作区':'绑定已失效'}</b></div><code>${esc(session.gitBranch||'detached')}</code><small title="${esc(session.worktreePath)}">${esc(session.worktreePath)}</small><div class="worktree-meta"><span>${session.changedFiles||0} 个修改</span><span>${esc((session.head||session.baseCommit||'').slice(0,8))}</span></div></div>`:`<div class="rail-card worktree-current shared"><div class="worktree-status"><span class="status-dot"></span><b>共享主工作区</b></div><small title="${esc(session?.executionCwd||'')}">${esc(session?.executionCwd||'选择会话后显示')}</small><p>创建会话分支时可选择独立 Worktree。</p></div>`
  list.innerHTML=state.worktreeGraph?.nodes?.length?renderVerticalWorktreeGraph():worktreeGraphState==='error'?'<div class="rail-empty rail-error">Git Commit Graph 读取失败，请点击 Worktree 图标重试</div>':'<div class="rail-empty">正在读取 Git Commit Graph…</div>'
  bindGraphNodes()
  renderGitManager();requestAnimationFrame(()=>{if(rail)rail.scrollTop=railTop;const next=$('.worktree-graph-scroll');if(next)next.scrollTop=worktreeGraphScrollTop})
}
async function loadGitStatus(){
  if(!state.active){gitUi.session=null;gitUi.status=null;renderGitManager();return}
  gitUi.session=state.active;gitUi.status=null;gitUi.notice='';gitUi.error=false;renderGitManager()
  try{gitUi.status=await api(`/api/sessions/${state.active}/git/status`)}catch(error){gitUi.notice=error.message;gitUi.error=true}
  renderGitManager()
}
function gitStatusLabel(file){if(file.conflict)return '冲突';if(file.untracked)return '未跟踪';const code=`${file.indexStatus||' '}${file.worktreeStatus||' '}`.trim();return code||'修改'}
function gitFileGroup(title,files,operation){
  if(!files.length)return ''
  const staged=operation==='unstage'
  return `<section class="git-file-group"><div class="git-group-head"><b>${title}</b><button class="git-batch" data-git-op="${operation}" data-git-all="true">${staged?'全部取消':'暂存全部'}</button></div>${files.map(file=>`<div class="git-file-row ${file.conflict?'conflict':''}"><button class="git-file-open" data-git-diff="${esc(file.path)}" data-git-staged="${staged}"><span class="git-state-code">${esc(gitStatusLabel(file))}</span><span title="${esc(file.path)}">${esc(file.path)}</span></button><button class="git-file-action" data-git-op="${operation}" data-git-path="${esc(file.path)}" title="${staged?'取消暂存':'暂存'}">${staged?'−':'＋'}</button></div>`).join('')}</section>`
}
function renderGitManager(){
  const host=$('#git-manager'),rail=$('#right-rail'),railTop=rail?.scrollTop||0;if(!host)return
  if(!state.active){host.innerHTML='<div class="rail-empty">选择会话后管理 Git 状态</div>';requestAnimationFrame(()=>{if(rail)rail.scrollTop=railTop});return}
  if(gitUi.session!==state.active||!gitUi.status){host.innerHTML=gitUi.notice?`<div class="git-notice error">${esc(gitUi.notice)}</div>`:'<div class="rail-empty">正在读取 Git 状态…</div>';requestAnimationFrame(()=>{if(rail)rail.scrollTop=railTop});return}
  const status=gitUi.status,files=status.files||[],staged=files.filter(file=>file.staged),unstaged=files.filter(file=>file.unstaged),color=branchColor(status.branch),remote=status.remote||((status.remotes||[]).includes('origin')?'origin':(status.remotes||[])[0]||''),sync=status.upstream?`${status.ahead} ↑ · ${status.behind} ↓`:'尚未关联远端',clean=!files.length
  const remoteOptions=(status.remotes||[]).map(name=>`<option value="${esc(name)}" ${name===remote?'selected':''}>${esc(name)}</option>`).join('')
  const commitEnabled=staged.length>0||unstaged.length>0,commitLabel=staged.length?'提交到本地':'暂存全部并提交';
  host.innerHTML=`<div class="git-head" style="--branch-color:${color}"><div><span class="git-branch-dot"></span><b>${esc(status.branch||'detached HEAD')}</b><small>${esc(sync)}</small></div><button id="git-refresh" class="git-icon-button" title="刷新 Git 状态">↻</button></div>${gitUi.notice?`<div class="git-notice ${gitUi.error?'error':'success'}">${esc(gitUi.notice)}</div>`:''}<div class="git-state-summary"><span>${clean?'工作区干净':`${files.length} 个变更`}</span><span>${staged.length} 已暂存</span></div>${gitFileGroup('未暂存',unstaged,'stage')}${gitFileGroup('已暂存',staged,'unstage')}${clean?'<div class="git-clean"><span>✓</span><b>没有待提交的修改</b></div>':''}<form id="git-commit-form" class="git-commit-form"><textarea id="git-commit-message" maxlength="2000" placeholder="提交信息" ${commitEnabled?'':'disabled'}></textarea><button type="submit" ${commitEnabled?'':'disabled'}>${commitLabel}</button></form><div class="git-push-row"><select id="git-remote" ${remoteOptions?'':'disabled'}>${remoteOptions||'<option>未配置远端</option>'}</select><button id="git-push" ${status.detached||(!status.upstream&&!remoteOptions)?'disabled':''}>${status.upstream?'推送':'发布分支'}</button></div><p class="git-auth-hint">推送复用系统 Git 凭据或 SSH Key，CodeAuto 不保存 Git 密钥。</p>`
  $('#git-refresh').onclick=loadGitStatus
  document.querySelectorAll('[data-git-op]').forEach(button=>button.onclick=()=>gitAction(button.dataset.gitOp,{all:button.dataset.gitAll==='true',paths:button.dataset.gitPath?[button.dataset.gitPath]:[]}))
  document.querySelectorAll('[data-git-diff]').forEach(button=>button.onclick=()=>loadGitDiff(button.dataset.gitDiff,button.dataset.gitStaged==='true'))
  $('#git-commit-form').onsubmit=async event=>{event.preventDefault();const message=$('#git-commit-message').value.trim();if(!message)return;if(!staged.length&&unstaged.length)await gitAction('stage',{all:true,paths:[]});if(message)gitAction('commit',{message})}
  $('#git-push').onclick=()=>gitAction('push',{remote:gitUi.status?.upstream?'':($('#git-remote').value||'')});requestAnimationFrame(()=>{if(rail)rail.scrollTop=railTop})
}
function previewLines(content,mode){
  return String(content??'').split('\n').map((line,index)=>{let kind='';if(mode==='diff'){if(line.startsWith('@@'))kind='hunk';else if(line.startsWith('+++')||line.startsWith('---')||line.startsWith('diff ')||line.startsWith('index '))kind='meta';else if(line.startsWith('+'))kind='added';else if(line.startsWith('-'))kind='removed'}return `<span class="git-code-line ${kind}"><i>${index+1}</i><code>${esc(line)||' '}</code></span>`}).join('')
}
function renderGitFileDialog(){
  $('#git-file-name').textContent=gitUi.previewPath||'文件';$('#git-file-context').textContent=gitUi.previewKind==='memory'?(gitUi.previewMode==='reflection'?'反思内容':'Bullet 内容'):gitUi.previewMode==='diff'?(gitUi.previewStaged?'已暂存 Diff':'工作区 Diff'):'当前文件完整内容';document.querySelectorAll('[data-git-preview]').forEach(button=>{if(gitUi.previewKind!=='memory')button.classList.toggle('active',button.dataset.gitPreview===gitUi.previewMode);button.disabled=gitUi.previewKind==='git'&&button.dataset.gitPreview==='diff'&&!gitUi.previewCanDiff});const host=$('#git-file-content');host.innerHTML=gitUi.previewLoading?'<div class="git-file-loading">正在读取文件…</div>':gitUi.previewKind==='memory'?`<article class="memory-markdown md">${gitUi.previewRenderedHtml||`<p>${esc(gitUi.previewContent).replace(/\n/g,'<br>')}</p>`}</article>`:`<div class="git-code-view ${gitUi.previewMode}">${previewLines(gitUi.previewContent,gitUi.previewMode)}</div>`;$('#git-file-footnote').textContent=gitUi.previewKind==='memory'?'只读预览 · 来源于本地 .codeauto 文件':gitUi.previewMode==='diff'?'绿色为新增，红色为删除':'只读预览 · 行号仅用于查看'
}
async function loadGitPreview(mode){
  const request=++gitUi.previewRequest;gitUi.previewMode=mode;gitUi.previewLoading=true;gitUi.previewContent='';renderGitFileDialog()
  try{let content;if(mode==='diff'){const body=await api(`/api/sessions/${gitUi.previewSession}/git/diff?path=${encodeURIComponent(gitUi.previewPath)}&staged=${gitUi.previewStaged}`);content=body.diff}else{const body=await api(`/api/files/content?sessionId=${encodeURIComponent(gitUi.previewSession)}&path=${encodeURIComponent(gitUi.previewPath)}`);content=body.content}if(request===gitUi.previewRequest)gitUi.previewContent=content}
  catch(error){if(request===gitUi.previewRequest)gitUi.previewContent=`无法读取：${error.message}`}
  finally{if(request===gitUi.previewRequest){gitUi.previewLoading=false;renderGitFileDialog()}}
}
function loadGitDiff(path,staged){gitUi.previewKind='git';gitUi.previewSession=state.active;gitUi.previewPath=path;gitUi.previewStaged=staged;gitUi.previewCanDiff=true;gitUi.previewMode='diff';gitUi.previewContent='';resetPreviewTabs();$('#git-file-dialog').showModal();loadGitPreview('diff')}
async function gitAction(operation,payload){
  if(gitUi.busy)return;gitUi.busy=true;gitUi.notice='正在处理…';gitUi.error=false;renderGitManager()
  try{gitUi.status=await api(`/api/sessions/${state.active}/git/${operation}`,{method:'POST',body:JSON.stringify(payload)});gitUi.notice=operation==='commit'?'本地提交已创建':operation==='push'?'推送完成':operation==='stage'?'已加入暂存区':'已取消暂存';await refresh();if(state.railView==='worktrees')await loadWorktreeGraph()}
  catch(error){gitUi.notice=error.message;gitUi.error=true}
  finally{gitUi.busy=false;renderGitManager()}
}
function renderPermissionApproval(){
  const approval=(state.pendingApprovals||[]).find(item=>item.sessionId===state.active),dialog=$('#permission-dialog')
  if(!approval){permissionUi.current=null;if(dialog.open)dialog.close();return}
  if(permissionUi.current===approval.id&&dialog.open)return
  permissionUi.current=approval.id;permissionUi.busy=false;$('#permission-title').textContent=approval.kind==='edit'?'允许修改文件？':'允许执行命令？';$('#permission-summary').textContent=approval.summary||'Agent 请求执行受保护操作';$('#permission-kind').textContent=approval.kind==='edit'?'文件修改':'命令';$('#permission-scope').textContent=approval.scope||'';$('#permission-feedback').value='';$('#permission-auto-policy').value='ASK';$('#permission-error').textContent=''
  document.querySelectorAll('[data-permission-decision]').forEach(button=>button.classList.toggle('hidden',!(approval.choices||[]).includes(button.dataset.permissionDecision)))
  if(!dialog.open)dialog.showModal()
}
async function resolvePermission(decision){
  const approval=(state.pendingApprovals||[]).find(item=>item.id===permissionUi.current);if(!approval||permissionUi.busy)return
  permissionUi.busy=true;document.querySelectorAll('.permission-actions button').forEach(button=>button.disabled=true);$('#permission-error').textContent='正在提交选择…'
  const feedback=$('#permission-feedback').value.trim(),resolved=decision==='DENY_ONCE'&&feedback?'DENY_WITH_FEEDBACK':decision
  try{await api(`/api/permissions/${approval.id}`,{method:'POST',body:JSON.stringify({decision:resolved,feedback,autoPolicy:$('#permission-auto-policy').value})});state.pendingApprovals=state.pendingApprovals.filter(item=>item.id!==approval.id);permissionUi.current=null;$('#permission-dialog').close();await refresh()}
  catch(error){$('#permission-error').textContent=error.message}
  finally{permissionUi.busy=false;document.querySelectorAll('.permission-actions button').forEach(button=>button.disabled=false)}
}
function openDeleteDialog(id){
  const session=state.sessions.find(item=>item.id===id);if(!session)return
  const dialog=$('#delete-dialog');$('#delete-session-id').value=id;$('#delete-heading').textContent=`删除「${session.title||'未命名会话'}」？`;$('#delete-description').textContent=session.worktreePath?`将同时删除会话、Worktree 和 Git 分支 ${session.gitBranch||''}。`:'将删除此会话的历史记录。此操作无法撤销。';$('#delete-force').checked=false;$('#delete-force-row').classList.add('hidden');$('#delete-error').textContent='';dialog.showModal()
}
async function submitDelete(event){
  event.preventDefault();const id=$('#delete-session-id').value,force=$('#delete-force').checked,submit=$('#delete-submit');submit.disabled=true;submit.textContent='正在删除…';$('#delete-error').textContent=''
  try{const body=await api(`/api/sessions/${id}?force=${force}`,{method:'DELETE'});state.expandedSessions.delete(id);state.active=body.active||null;$('#delete-dialog').close();state.transcript=[];transcriptUi.signature='';await refresh()}
  catch(error){$('#delete-error').textContent=error.message;const session=state.sessions.find(item=>item.id===id);if(session?.worktreePath)$('#delete-force-row').classList.remove('hidden')}
  finally{submit.disabled=false;submit.textContent='删除'}
}
function normalizeContent(value){if(typeof value==='string')return value;if(Array.isArray(value))return value.map(x=>x?.text||'').filter(Boolean).join('\n');if(value&&typeof value==='object')return value.text||JSON.stringify(value,null,2);return String(value??'')}
function markdownHtml(message,content){return message.renderedHtml||`<p>${esc(content).replace(/\n/g,'<br>')}</p>`}
function messageHtml(m){const role=m.role||m.kind||'';const body=m.content??m.text??m.body??'';const content=normalizeContent(body);if(role==='user')return `<div class="bubble user">${esc(content)}</div>`;if(role==='assistant'||role==='assistant_raw'||role==='context_summary')return content.trim()?`<div class="bubble assistant md">${markdownHtml(m,content)}</div>`:'';if(role==='progress'||role==='assistant_progress')return content.trim()?`<div class="bubble progress md">${markdownHtml(m,content)}</div>`:'';if(role==='tool'||role==='tool_result')return `<details class="tool ${m.isError||m.status==='error'?'error':''}"><summary class="tool-head"><b>${esc(m.toolName||'tool')}</b><span>${m.isError||m.status==='error'?'错误':'工具结果 · 点击展开'}</span></summary><div class="tool-body">${esc(content)}</div></details>`;if(role==='assistant_tool_call')return `<details class="tool"><summary class="tool-head"><b>${esc(m.toolName)}</b><span>工具调用 · 点击展开</span></summary><div class="tool-body">${esc(JSON.stringify(m.input,null,2))}</div></details>`;return ''}
function rawBlocks(message){return Array.isArray(message.content)?message.content:[]}
function embeddedToolCalls(message){return rawBlocks(message).filter(block=>block?.type==='tool_use')}
function agentText(message){const role=message.role||message.kind||'';return ['assistant','assistant_raw','context_summary','progress','assistant_progress','assistant_stream'].includes(role)&&normalizeContent(message.content??message.text??message.body??'').trim()!==''}
function finalAgentText(message){const role=message.role||message.kind||'';return ['assistant','assistant_raw','context_summary'].includes(role)&&normalizeContent(message.content??message.text??message.body??'').trim()!==''}
function toolActivity(message){const role=message.role||message.kind||'';return role==='tool'||role==='tool_result'||role==='assistant_tool_call'||embeddedToolCalls(message).length>0}
function processMessageHtml(message){
  const role=message.role||message.kind||'',parts=[]
  if(role==='thinking_live')parts.push(`<div class="thinking-block live"><span>正在思考</span><p>${esc(normalizeContent(message.content))}</p></div>`)
  for(const block of rawBlocks(message))if(block?.type==='thinking'&&block.thinking)parts.push(`<div class="thinking-block"><span>思考</span><p>${esc(block.thinking)}</p></div>`)
  if(agentText(message))parts.push(`<div class="process-note md">${markdownHtml(message,normalizeContent(message.content??message.text??message.body??''))}</div>`)
  for(const call of embeddedToolCalls(message))parts.push(`<details class="tool tool-call"><summary class="tool-head"><b>${esc(call.name||'tool')}</b><span>工具调用 · 点击展开</span></summary><div class="tool-body">${esc(JSON.stringify(call.input??{},null,2))}</div></details>`)
  if(role==='tool'||role==='tool_result'||role==='assistant_tool_call')parts.push(messageHtml(message))
  return parts.join('')
}
function turnGroups(messages){
  const turns=[]
  for(const message of messages){const role=message.role||message.kind||'';if(role==='system')continue;if(role==='user'||!turns.length)turns.push({user:role==='user'?message:null,messages:[]});if(role!=='user')turns.at(-1).messages.push(message)}
  return turns
}
function turnHtml(turn,index,running){
  const messages=turn.messages,lastTool=Math.max(-1,...messages.map((message,i)=>toolActivity(message)?i:-1)),finalIndex=running?-1:messages.reduce((found,message,i)=>finalAgentText(message)&&i>lastTool?i:found,-1),finalMessage=finalIndex>=0?messages[finalIndex]:null,process=messages.filter((_,i)=>i!==finalIndex),toolCount=process.reduce((count,message)=>count+(embeddedToolCalls(message).length||((message.role||message.kind)==='assistant_tool_call'?1:0)),0),resultCount=process.filter(message=>['tool','tool_result'].includes(message.role||message.kind||'')).length,processHtml=process.map(processMessageHtml).join(''),summary=running?'Agent 正在执行':`查看执行过程${toolCount||resultCount?` · ${toolCount||resultCount} 次工具`:''}`
  return `<section class="chat-turn" data-turn-index="${index}">${turn.user?messageHtml(turn.user):''}${processHtml?`<details class="turn-process ${running?'running':''}" ${running?'open':''}><summary><span class="process-indicator">${running?'●':'◇'}</span><b>${summary}</b><span class="process-chevron">⌄</span></summary><div class="turn-process-body">${processHtml}</div></details>`:''}${finalMessage?`<div class="turn-final"><div class="turn-final-label">Agent 最终回复</div>${messageHtml(finalMessage)}</div>`:''}</section>`
}
function transcriptSignature(session){return `${state.active}:${session?.running?1:0}:${JSON.stringify(state.transcript)}`}
function renderTranscript(session){
  const host=$('#transcript'),signature=transcriptSignature(session);if(signature===transcriptUi.signature)return
  const switched=transcriptUi.session!==state.active,wasPinned=transcriptUi.pinned,hostRect=host.getBoundingClientRect(),anchor=[...host.querySelectorAll('.chat-turn')].find(row=>row.getBoundingClientRect().bottom>hostRect.top),anchorIndex=anchor?.dataset.turnIndex,anchorTop=anchor?anchor.getBoundingClientRect().top-hostRect.top:0
  const turns=turnGroups(state.transcript);host.innerHTML=turns.length?turns.map((turn,index)=>turnHtml(turn,index,!!session?.running&&index===turns.length-1)).join(''):'<div class="empty"><div class="empty-icon">✦</div><h2>开始一次新的工程对话</h2><p>让 Agent 阅读代码、修改文件或解释当前工作区。所有工具调用都会留下可追溯记录。</p></div>'
  transcriptUi.session=state.active;transcriptUi.signature=signature
  requestAnimationFrame(()=>{if(switched||wasPinned){host.scrollTop=host.scrollHeight;transcriptUi.pinned=true;return}const restored=anchorIndex===undefined?null:host.querySelector(`[data-turn-index="${anchorIndex}"]`);if(restored)host.scrollTop+=restored.getBoundingClientRect().top-host.getBoundingClientRect().top-anchorTop})
}
$('#transcript').addEventListener('scroll',()=>{const host=$('#transcript');transcriptUi.pinned=host.scrollHeight-host.clientHeight-host.scrollTop<=32},{passive:true})
async function refresh(){const s=await api('/api/state');state.sessions=s.sessions;state.metrics=s.metrics;state.contextWindow=s.contextWindow||0;state.gitAvailable=!!s.gitAvailable;state.rootChangedFiles=s.rootChangedFiles||0;state.worktrees=s.worktrees||[];if(s.worktreeGraph)state.worktreeGraph=s.worktreeGraph;state.pendingApprovals=s.pendingApprovals||[];if(!state.active&&state.sessions[0])state.active=state.sessions[0].id;if(!state.treeReady){ensureActivePathExpanded();state.treeReady=true}render();const active=state.sessions.find(x=>x.id===state.active),tasks=[];if(state.active&&(!active?.running||!state.transcript.length))tasks.push(loadTranscript().then(()=>render()));if(state.active)tasks.push(Promise.allSettled([loadBoardData(),loadReflectionData()]).then(()=>render()));await Promise.allSettled(tasks)}
function showRailStatus(message,error=false){const box=$('#rail-eval');box.innerHTML=`<span class="${error?'rail-error':'rail-success'}">${esc(message)}</span>`;setTimeout(()=>render(),2600)}
function renderBoard(){
  const session=state.sessions.find(x=>x.id===state.active),evaluation=state.evaluation||{},m=evaluation.metrics||{},scope=state.boardMode==='project'?'当前项目':'当前会话',series=evaluation.contextSeries||[],values=series.map(point=>Number(point.tokens)||0),max=Math.max(...values,1),poly=values.length?values.map((v,i)=>`${values.length===1?110:i*220/(values.length-1)},${48-(v/max)*42}`).join(' '):''
  const reflections=state.reflections||{reflections:[],bullets:[]},reflectionScope=state.reflectionMode==='project'?'当前项目':'当前会话',reflectionRows=(reflections.reflections||[]).slice(0,12)
  const signature=JSON.stringify([state.active,state.boardMode,state.reflectionMode,evaluation,reflections]);if(signature===boardUi.signature)return;const oldList=$('.reflection-list');if(oldList)boardUi.reflectionScrollTop=oldList.scrollTop;boardUi.signature=signature
  $('#board').innerHTML=`<div class="board-toolbar"><div><b>运行评估</b><small>${scope} · 指标只统计选定范围</small></div><div class="board-scope"><button class="${state.boardMode==='session'?'active':''}" data-board-scope="session">当前会话</button><button class="${state.boardMode==='project'?'active':''}" data-board-scope="project">当前项目</button></div></div><div class="board-grid">${[['turns','完成轮次'],['toolCalls','工具调用'],['errors','错误/拒绝'],['contextTokens','上下文 tokens']].map(([k,l])=>`<div class="board-card"><b>${(m[k]||0).toLocaleString()}</b><span>${l}</span></div>`).join('')}</div><div class="board-chart"><div class="board-chart-head"><b>上下文与运行趋势</b><span>横轴：上下文事件序号 · 纵轴：tokens · ${evaluation.seriesAvailable?'已读取真实事件':'当前范围暂无上下文事件'}</span></div><svg viewBox="0 0 220 54" preserveAspectRatio="none" aria-label="横轴为上下文事件序号，纵轴为 tokens"><line x1="0" y1="49" x2="220" y2="49" stroke="#dfe6ef"/><polyline points="${poly}" fill="none" stroke="#246bfd" stroke-width="2" vector-effect="non-scaling-stroke"/></svg><div class="chart-legend"><span><i class="legend-line blue"></i>上下文 tokens</span><span><i class="legend-line green"></i>缓存/压缩事件</span><span><i class="legend-line amber"></i>工具错误</span></div></div><details class="reflection-panel" open><summary><b>反思与 Bullet</b><span>${reflectionRows.length?'已读取本地记录':(reflections.scopeNote||'当前范围暂无记录')}</span></summary><div class="reflection-scope"><button class="${state.reflectionMode==='session'?'active':''}" data-reflection-scope="session">当前会话</button><button class="${state.reflectionMode==='project'?'active':''}" data-reflection-scope="project">当前项目</button></div><div class="reflection-list">${reflectionRows.length?reflectionRows.map(item=>`<button class="memory-row" data-memory-id="${esc(item.id)}" data-memory-type="${item.bulletId?'bullet':'reflection'}"><small>${item.bulletId?'Bullet':'反思'} · ${esc(item.updatedAt||'')}</small><b>${esc(item.title||'未命名')}</b></button>`).join(''):`<div class="reflection-empty">${esc(reflections.scopeNote||'没有找到对应范围的反思或 Bullet 文件')}</div>`}</div></details>`
  document.querySelectorAll('[data-memory-id]').forEach(button=>button.onclick=()=>{const item=(state.reflections?.reflections||[]).find(x=>x.id===button.dataset.memoryId);if(item)openMemoryPreview(item,'reflection')})
  document.querySelectorAll('[data-board-scope]').forEach(button=>button.onclick=async()=>{state.boardMode=button.dataset.boardScope;await loadBoardData();renderBoard()})
  document.querySelectorAll('[data-reflection-scope]').forEach(button=>button.onclick=async()=>{state.reflectionMode=button.dataset.reflectionScope;await loadReflectionData();renderBoard()})
  requestAnimationFrame(()=>{const list=$('.reflection-list');if(list)list.scrollTop=boardUi.reflectionScrollTop})
}
async function loadBoardData(){state.evaluation=await api(`/api/evaluation?scope=${state.boardMode}&sessionId=${encodeURIComponent(state.active||'')}`)}
async function loadReflectionData(){state.reflections=await api(`/api/reflections?scope=${state.reflectionMode}&sessionId=${encodeURIComponent(state.active||'')}`)}
async function renderTodos(){try{
  const body=await api(`/api/todos?sessionId=${encodeURIComponent(state.active||'')}`),history=state.todoMode==='history'
  document.querySelectorAll('.todo-tab').forEach(btn=>btn.classList.toggle('active',btn.dataset.todoView===state.todoMode))
  const groups=(body.groups||[]).map(group=>({...group,visibleEntries:group.entries.filter(item=>history?item.status==='completed':item.status!=='completed')})).filter(group=>group.visibleEntries.length)
  $('#rail-todo').innerHTML=groups.length?groups.slice(0,4).map(g=>`<div class="todo-group"><div class="todo-group-head"><b title="${esc(g.title)}">${esc(g.title)}</b><span>${g.visibleEntries.length} 项</span></div>${g.visibleEntries.slice(0,6).map(t=>`<div class="todo-row"><button class="todo-check ${t.status}" data-todo-id="${esc(t.id)}" data-todo-op="${t.status==='completed'?'restore':'complete'}" title="${t.status==='completed'?'恢复到当前':'标记完成'}">${t.status==='completed'?'✓':''}</button><span class="todo-text ${t.status==='completed'?'done':''}" title="${esc(t.content)}">${esc(t.content)}</span><button class="todo-delete" data-todo-id="${esc(t.id)}" data-todo-op="delete" title="删除">×</button></div>`).join('')}</div>`).join(''):`<div class="todo-empty"><span>${history?'✓':'○'}</span>${history?'暂无历史 Todo':'当前没有待处理 Todo'}</div>`
  document.querySelectorAll('[data-todo-op]').forEach(btn=>btn.onclick=async()=>{btn.disabled=true;try{await api(`/api/todos/${btn.dataset.todoId}`,{method:'POST',body:JSON.stringify({operation:btn.dataset.todoOp,sessionId:state.active})});await renderTodos();showRailStatus('Todo 已更新')}catch(err){btn.disabled=false;showRailStatus(err.message,true)}})
}catch(err){$('#rail-todo').textContent='Todo 暂不可用';$('#rail-eval').innerHTML=`<span class="rail-error">${esc(err.message)}</span>`}}
let renameBusy=false
function closeRename(){const input=$('#rename-input');input.classList.add('hidden');$('#session-title').classList.remove('hidden');$('#rename-session').classList.remove('hidden');renameBusy=false}
function beginRename(){const session=state.sessions.find(x=>x.id===state.active);if(!session)return;const input=$('#rename-input');input.value=session.title||'';$('#session-title').classList.add('hidden');$('#rename-session').classList.add('hidden');input.classList.remove('hidden');input.focus();input.select()}
async function saveRename(){if(renameBusy)return;const session=state.sessions.find(x=>x.id===state.active),title=$('#rename-input').value.replace(/\s+/g,' ').trim();if(!session||!title||title===session.title){closeRename();return}renameBusy=true;try{const body=await api(`/api/sessions/${session.id}/rename`,{method:'POST',body:JSON.stringify({title})});state.active=body.active||state.active;closeRename();await refresh();showRailStatus('会话标题已更新')}catch(err){renameBusy=false;showRailStatus(err.message,true);$('#rename-input').focus()}}
async function selectSession(id){state.active=id;state.events=[];ensureActivePathExpanded();await loadTranscript();await Promise.allSettled([loadBoardData(),loadReflectionData()]);render();if(state.railView==='files')loadFiles();if(state.railView==='worktrees')loadGitStatus()}
async function loadTranscript(){if(!state.active)return;const [transcript,trace]=await Promise.all([api(`/api/sessions/${state.active}/transcript`),api(`/api/sessions/${state.active}/trace`)]);state.transcript=transcript;state.events=trace.events||[]}
let newSessionBusy=false
async function newSession(){
  if(newSessionBusy)return
  newSessionBusy=true;newSessionButton.disabled=true
  try{const s=await api('/api/sessions',{method:'POST',body:'{}'});state.active=s.active||null;await refresh()}
  catch(err){showRailStatus(err.message,true)}
  finally{newSessionBusy=false;newSessionButton.disabled=false}
}
let renderFrame=0
let refreshTimer=0
const seenEventIds=new Set()
function scheduleRender(){if(renderFrame)return;renderFrame=requestAnimationFrame(()=>{renderFrame=0;render()})}
function scheduleRefresh(delay=180){if(refreshTimer)return;refreshTimer=setTimeout(()=>{refreshTimer=0;refresh().catch(()=>{})},delay)}
function appendStream(role,delta){const last=state.transcript.at(-1);if(last?.role===role)last.content+=delta;else state.transcript.push({role,content:delta})}
function streamKey(content){return normalizeContent(content).replace(/\s+/g,' ').trim()}
function consumeCurrentStream(){for(let i=state.transcript.length-1;i>=0;i--){const message=state.transcript[i],role=message.role||message.kind||'';if(role==='assistant_stream'){state.transcript.splice(i,1);return}if(toolActivity(message)||role==='user')return}}
function addEvent(e){if(e.eventId&&seenEventIds.has(e.eventId))return;if(e.eventId){seenEventIds.add(e.eventId);if(seenEventIds.size>1000)seenEventIds.delete(seenEventIds.values().next().value)}state.events.push(e);if(e.type==='thinking_delta')appendStream('thinking_live',e.payload.delta||'');if(e.type==='assistant_delta')appendStream('assistant_stream',e.payload.delta||'');if(e.type==='assistant_message'){consumeCurrentStream();state.transcript.push({role:'assistant',content:e.payload.content,renderedHtml:e.payload.renderedHtml})}if(e.type==='progress'){consumeCurrentStream();const last=state.transcript.at(-1);if((last?.role||last?.kind)==='progress'&&streamKey(last.content)===streamKey(e.payload.content)){last.renderedHtml=e.payload.renderedHtml}else state.transcript.push({role:'progress',content:e.payload.content,renderedHtml:e.payload.renderedHtml})}if(e.type==='user_message')state.transcript.push({role:'user',content:e.payload.content});if(e.type==='tool_start')state.transcript.push({role:'assistant_tool_call',toolName:e.payload.name,input:e.payload.input});if(e.type==='tool_result'){state.transcript.push({role:'tool_result',toolName:e.payload.name,content:e.payload.output,isError:e.payload.error});if(state.railView==='files')loadFiles()}scheduleRender()}
$('#new-session').onclick=newSession
$('#toggle-left').onclick=()=>{leftCollapsed=!leftCollapsed;applyShellState();saveShellState();queueMapLinks()}
$('#toggle-right').onclick=()=>{rightCollapsed=!rightCollapsed;applyShellState();saveShellState();queueMapLinks()}
document.querySelectorAll('.rail-tab').forEach(button=>button.onclick=()=>setRailView(button.dataset.railView))
$('#config-form').onsubmit=saveConfig
$('#key-form').onsubmit=event=>{event.preventDefault();saveKey(false)}
$('#clear-key').onclick=()=>saveKey(true)
$('#fork-form').onsubmit=submitFork
$('#fork-cancel').onclick=()=>$('#fork-dialog').close()
$('#fork-dialog').onclick=event=>{if(event.target===$('#fork-dialog'))$('#fork-dialog').close()}
document.querySelectorAll('input[name="fork-mode"]').forEach(input=>input.onchange=updateForkMode)
$('#delete-session').onclick=()=>openDeleteDialog(state.active)
$('#delete-form').onsubmit=submitDelete
$('#delete-cancel').onclick=()=>$('#delete-dialog').close()
$('#delete-dialog').onclick=event=>{if(event.target===$('#delete-dialog'))$('#delete-dialog').close()}
$('#git-file-close').onclick=()=>$('#git-file-dialog').close()
$('#git-file-dialog').onclick=event=>{if(event.target===$('#git-file-dialog'))$('#git-file-dialog').close()}
document.querySelectorAll('[data-git-preview]').forEach(button=>button.onclick=()=>{if(gitUi.previewKind==='memory'){const wanted=button.dataset.gitPreview==='diff'?'reflection':'bullet',item=gitUi.memoryItems.find(x=>x.memoryType===wanted);if(item)openMemoryPreview(item,wanted)}else loadGitPreview(button.dataset.gitPreview)})
$('#git-file-copy').onclick=async()=>{try{await navigator.clipboard.writeText(gitUi.previewContent||'');$('#git-file-copy').textContent='已复制';setTimeout(()=>{$('#git-file-copy').textContent='复制'},1200)}catch{$('#git-file-footnote').textContent='浏览器未允许复制，请在内容区手动选择'}}
$('#permission-dialog').addEventListener('cancel',event=>event.preventDefault())
document.querySelectorAll('[data-permission-decision]').forEach(button=>button.onclick=()=>resolvePermission(button.dataset.permissionDecision))
$('[data-permission-action="deny"]').onclick=()=>resolvePermission('DENY_ONCE')
$('#permission-auto-policy').onchange=event=>$('#permission-dialog').classList.toggle('auto-all',event.target.value==='ALL')
$('#rename-session').onclick=beginRename
$('#rename-input').onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();saveRename()}if(e.key==='Escape'){e.preventDefault();closeRename()}}
$('#rename-input').onblur=()=>{if(!renameBusy)saveRename()}
document.querySelectorAll('.todo-tab').forEach(btn=>btn.onclick=()=>{state.todoMode=btn.dataset.todoView;renderTodos()})
$('#export-trace').onclick=()=>{if(state.active)window.open(`/api/sessions/${state.active}/trace`,'_blank')}
$('#composer').onsubmit=async e=>{e.preventDefault();const text=$('#prompt').value.trim(),session=state.sessions.find(item=>item.id===state.active);if(!text||!session||session.running)return;session.running=true;render();try{await api(`/api/sessions/${state.active}/messages`,{method:'POST',body:JSON.stringify({content:text})});$('#prompt').value='';await refresh()}catch(error){session.running=false;showRailStatus(error.message,true);render()}}
$('#prompt').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();$('#composer').requestSubmit()}}
document.querySelectorAll('.view').forEach(btn=>btn.onclick=()=>{document.querySelectorAll('.view').forEach(x=>x.classList.remove('active'));btn.classList.add('active');document.querySelectorAll('.view-panel').forEach(x=>x.classList.add('hidden'));$(`#${btn.dataset.view}-view`).classList.remove('hidden');if(btn.dataset.view==='map')queueMapLinks();if(btn.dataset.view==='board')renderBoard()})
window.addEventListener('resize',queueMapLinks)
const stream=new EventSource('/api/events');stream.addEventListener('agent_event',e=>{try{const event=JSON.parse(e.data);if(!state.active)state.active=event.sessionId;if(event.sessionId===state.active)addEvent(event);scheduleRefresh()}catch{}});stream.onopen=()=>{$('#connection').textContent='实时连接'};stream.onerror=()=>{$('#connection').textContent='连接重试中';$('#connection').classList.add('busy')}
refresh().catch(e=>{$('#connection').textContent='后端未连接';console.error(e)});setInterval(()=>refresh().catch(()=>{}),5000)
