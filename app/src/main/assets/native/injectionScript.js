(() => {
    // Cloudflare Access: patch fetch + XMLHttpRequest to add the CF-Access
    // service-token headers for requests to the server host. WebView cannot add
    // headers to subresource requests natively, so this JS shim covers the
    // webapp's API calls (including POST bodies, which shouldInterceptRequest
    // cannot forward in modern API levels). Values come from the CfAccessJsBridge.
    const bridge = window.CfAccessJsBridge;
    if (bridge && bridge.isEnabled && bridge.isEnabled()) {
        const clientId = bridge.getClientId();
        const clientSecret = bridge.getClientSecret();

        const shouldAddHeaders = (url) => {
            try {
                const host = new URL(url, window.location.href).host;
                return host.endsWith('.ffilt.us');
            } catch (e) {
                return false;
            }
        };

        const addCfHeaders = (headers) => {
            headers.set('CF-Access-Client-Id', clientId);
            headers.set('CF-Access-Client-Secret', clientSecret);
        };

        // Patch window.fetch
        const originalFetch = window.fetch;
        if (originalFetch) {
            window.fetch = function (input, init) {
                const url = typeof input === 'string' ? input : (input && input.url);
                if (url && shouldAddHeaders(url)) {
                    init = init || {};
                    const headers = new Headers(init.headers || {});
                    addCfHeaders(headers);
                    init.headers = headers;
                }
                return originalFetch.call(this, input, init);
            };
        }

        // Patch XMLHttpRequest.open
        const originalOpen = XMLHttpRequest.prototype.open;
        if (originalOpen) {
            XMLHttpRequest.prototype.open = function (method, url, async, user, password) {
                if (shouldAddHeaders(url)) {
                    this.__cfHeaders = true;
                }
                return originalOpen.apply(this, arguments);
            };
        }
        const originalSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
        if (originalSetRequestHeader) {
            XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
                if (this.__cfHeaders && (name === 'CF-Access-Client-Id' || name === 'CF-Access-Client-Secret')) {
                    // Already set by the shim in send()
                    return;
                }
                return originalSetRequestHeader.apply(this, arguments);
            };
        }
        const originalSend = XMLHttpRequest.prototype.send;
        if (originalSend) {
            XMLHttpRequest.prototype.send = function (body) {
                if (this.__cfHeaders) {
                    try {
                        this.setRequestHeader('CF-Access-Client-Id', clientId);
                        this.setRequestHeader('CF-Access-Client-Secret', clientSecret);
                    } catch (e) {
                        // Headers already sent — ignore
                    }
                    this.__cfHeaders = false;
                }
                return originalSend.call(this, body);
            };
        }
    }

    const scripts = [
        '/native/nativeshell.js',
        '/native/EventEmitter.js',
        document.currentScript.src.concat('?deferred=true&ts=', Date.now())
    ];
    for (const script of scripts) {
        const scriptElement = document.createElement('script');
        scriptElement.src = script;
        scriptElement.charset = 'utf-8';
        scriptElement.setAttribute('defer', '');
        document.body.appendChild(scriptElement);
    }
    document.currentScript.remove();
})();
