package com.github.tvbox.osc.util;

import android.net.Uri;
import android.text.TextUtils;

import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;

import org.json.JSONObject;

/**
 * 处理openlist类型的源地址获取流程：
 * 1. 用账号密码POST登录获取token
 * 2. 用token POST请求获取raw_url
 * 3. 从raw_url下载配置内容
 *
 * 也提供统一的fetchContent方法，根据mode自动选择openlist或直接GET
 */
public class OpenListUtil {

    public interface OpenListCallback {
        void onSuccess(String content);
        void onError(String msg);
    }

    /**
     * 根据mode选择获取方式：openlist走登录流程，否则直接GET
     */
    public static void fetchContent(String url, String mode, String username, String password, OpenListCallback callback) {
        if ("openlist".equals(mode)) {
            fetchConfig(url, username, password, callback);
        } else {
            // 默认模式：直接GET
            OkGo.<String>get(url)
                    .headers("User-Agent", UA.random())
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() == null) return "";
                            return response.body().string();
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String content = response.body();
                            if (TextUtils.isEmpty(content)) {
                                callback.onError("内容为空");
                            } else {
                                callback.onSuccess(content);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            String msg = "请求失败";
                            if (response.getException() != null) {
                                msg += " - " + response.getException().getMessage();
                            }
                            callback.onError(msg);
                        }
                    });
        }
    }

    public static void fetchConfig(String apiUrl, String username, String password, OpenListCallback callback) {
        if (TextUtils.isEmpty(apiUrl) || TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            callback.onError("openlist: 源地址/用户名/密码不能为空");
            return;
        }

        // 解析出域名和路径
        Uri uri;
        try {
            uri = Uri.parse(apiUrl);
        } catch (Exception e) {
            callback.onError("openlist: 源地址格式错误");
            return;
        }

        String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath() != null ? uri.getPath() : "/";
        if (uri.getQuery() != null) {
            path += "?" + uri.getQuery();
        }

        if (TextUtils.isEmpty(host)) {
            callback.onError("openlist: 无法解析域名");
            return;
        }

        String baseUrl = scheme + "://" + host;
        if (port != -1) {
            baseUrl += ":" + port;
        }

        // 第一步：登录获取token
        loginAndGetToken(baseUrl, username, password, path, callback);
    }

    private static void loginAndGetToken(String baseUrl, String username, String password, String sourcePath, OpenListCallback callback) {
        String loginUrl = baseUrl + "/api/auth/login";
        String loginBody;
        try {
            JSONObject json = new JSONObject();
            json.put("username", username);
            json.put("password", password);
            loginBody = json.toString();
        } catch (Exception e) {
            callback.onError("openlist: 构建登录请求失败");
            return;
        }

        OkGo.<String>post(loginUrl)
                .upJson(loginBody)
                .headers("User-Agent", UA.random())
                .headers("Content-Type", "application/json")
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() == null) return "";
                        return response.body().string();
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String body = response.body();
                        if (TextUtils.isEmpty(body)) {
                            callback.onError("openlist: 登录返回为空");
                            return;
                        }
                        try {
                            JSONObject json = new JSONObject(body);
                            JSONObject data = json.optJSONObject("data");
                            String token = data != null ? data.optString("token", "") : "";
                            if (TextUtils.isEmpty(token)) {
                                callback.onError("openlist: 登录响应中未找到token");
                                return;
                            }
                            // 第二步：用token获取raw_url
                            getRawUrl(baseUrl, token, sourcePath, callback);
                        } catch (Exception e) {
                            callback.onError("openlist: 解析登录响应失败: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        String msg = "openlist: 登录请求失败";
                        if (response.getException() != null) {
                            msg += " - " + response.getException().getMessage();
                        }
                        callback.onError(msg);
                    }
                });
    }

    private static void getRawUrl(String baseUrl, String token, String sourcePath, OpenListCallback callback) {
        String fsUrl = baseUrl + "/api/fs/get";
        String fsBody;
        try {
            JSONObject json = new JSONObject();
            json.put("path", sourcePath);
            fsBody = json.toString();
        } catch (Exception e) {
            callback.onError("openlist: 构建fs/get请求失败");
            return;
        }

        OkGo.<String>post(fsUrl)
                .upJson(fsBody)
                .headers("User-Agent", UA.random())
                .headers("Content-Type", "application/json")
                .headers("Authorization", token)
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() == null) return "";
                        return response.body().string();
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String body = response.body();
                        if (TextUtils.isEmpty(body)) {
                            callback.onError("openlist: fs/get返回为空");
                            return;
                        }
                        try {
                            JSONObject json = new JSONObject(body);
                            JSONObject data = json.optJSONObject("data");
                            if (data == null) {
                                callback.onError("openlist: fs/get响应中未找到data字段");
                                return;
                            }
                            String rawUrl = data.optString("raw_url", "");
                            if (TextUtils.isEmpty(rawUrl)) {
                                callback.onError("openlist: fs/get响应中未找到raw_url");
                                return;
                            }
                            // 第三步：从raw_url下载配置内容
                            downloadRawUrl(rawUrl, callback);
                        } catch (Exception e) {
                            callback.onError("openlist: 解析fs/get响应失败: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        String msg = "openlist: fs/get请求失败";
                        if (response.getException() != null) {
                            msg += " - " + response.getException().getMessage();
                        }
                        callback.onError(msg);
                    }
                });
    }

    private static void downloadRawUrl(String rawUrl, OpenListCallback callback) {
        OkGo.<String>get(rawUrl)
                .headers("User-Agent", UA.random())
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() == null) return "";
                        return response.body().string();
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        if (TextUtils.isEmpty(content)) {
                            callback.onError("openlist: raw_url内容为空");
                        } else {
                            callback.onSuccess(content);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        String msg = "openlist: 下载raw_url失败";
                        if (response.getException() != null) {
                            msg += " - " + response.getException().getMessage();
                        }
                        callback.onError(msg);
                    }
                });
    }
}
