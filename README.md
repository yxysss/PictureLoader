# PictureLoader
A easy-use picture loader
使用PictureLoader.getPictureLoader(Context context)方法获取实例，然后调用ImageRequest(String url, ImageView imageview, int reqWidth, int reqHeight)方法，传入参数1.下载图片的url 2.图片传入的ImageView 3.图片需要的宽度 4.图片需要的高度，使用结束后，调用Destroy方法关闭PictureLoader。

PictureLoader使用三级缓存，网络-本地-内存，针对下载的图片进行压缩，防止OOM。
