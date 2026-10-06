// 示例插件：验证注入链路是否生效。
// 注入后会在页面右下角打印一条角标与日志。
(function () {
  console.log('[CFA plugin] hello injected');
  try {
    var badge = document.createElement('div');
    badge.textContent = '插件已注入 ✓';
    badge.style.cssText = 'position:fixed;right:12px;bottom:12px;background:#00E5A0;' +
      'color:#1B1F23;padding:6px 10px;border-radius:6px;font-size:12px;font-weight:bold;z-index:9999;';
    document.body.appendChild(badge);
  } catch (e) {
    console.log('[CFA plugin] badge failed: ' + e);
  }
})();