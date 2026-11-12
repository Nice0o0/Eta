// Shizuku UserService：在 Shizuku 提供的 shell（uid 2000）进程内执行命令。
// 事务号 16777114 是 Shizuku 服务端固定用于销毁 UserService 的方法，必须保留。
package io.github.mangi.eta.agent.device.shizuku;

import android.os.ParcelFileDescriptor;

interface IEtaShellService {

    void destroy() = 16777114;

    /**
     * 启动进程，返回句柄；0 表示启动失败。dir 为 null 时使用 server 当前目录。
     * token 用于定位 shell 自己写出的归属标记文件。
     */
    int spawn(in String[] command, in String[] env, String dir, String token);

    /**
     * 读取 shell 写入的归属标记（"<pid> group|tree|unavailable"）；读取失败返回 null。
     */
    String trackedOwner(int handle);

    /**
     * 标准输出读取端；调用方读完关闭即可得到真正的 EOF。
     */
    ParcelFileDescriptor outputStreamOf(int handle);

    /**
     * 标准错误读取端。
     */
    ParcelFileDescriptor errorStreamOf(int handle);

    /**
     * 标准输入写入端；调用方关闭后 server 结束子进程 stdin，进程可继续收尾。
     */
    ParcelFileDescriptor inputStreamOf(int handle);

    int waitFor(int handle);

    int exitValue(int handle);

    boolean alive(int handle);

    void kill(int handle);

    void release(int handle);
}
