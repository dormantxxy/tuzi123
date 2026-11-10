// Shizuku UserService：在 Shizuku（root）进程中执行 shell 命令。
// 与 MobiCode Studio 相同的实现方式，避免依赖 Shizuku.newProcess（该 API 在 13.x 为私有且会被 R8 移除）。
package me.rerere.rikkahub.shizuku;

import android.os.Bundle;

interface IShizukuShellService {
    void destroy();

    Bundle exec(in List<String> argv, in List<String> environment, String workingDirectory, int timeoutSeconds);

    int getUid();
}
