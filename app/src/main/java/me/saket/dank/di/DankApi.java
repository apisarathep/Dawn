package me.saket.dank.di;

import androidx.annotation.CheckResult;

import io.reactivex.Single;
import me.saket.dank.data.StreamableVideoResponse;
import me.saket.dank.ui.giphy.GiphySearchResponse;
import me.saket.dank.ui.media.ImgurAlbumResponse;
import me.saket.dank.ui.media.ImgurImageResponse;
import me.saket.dank.ui.media.ImgurUploadResponse;
import me.saket.dank.ui.media.gfycat.GfycatOauthResponse;
import me.saket.dank.ui.media.gfycat.GfycatResponse;
import me.saket.dank.ui.media.redgifs.RedgifsResponse;
import okhttp3.MultipartBody;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface DankApi {

  String HEADER_IMGUR_AUTH = "Authorization: Client-ID 87450e5590435e9";
  String GIPHY_API_KEY = "SFGHZ6SYGn3AzZ07b2tNpENCEDdYTzpB";

// ======== IMGUR ======== //

  @CheckResult
  @GET("https://api.imgur.com/3/album/{albumId}")
  @Headers({ HEADER_IMGUR_AUTH })
  Single<Response<ImgurAlbumResponse>> imgurAlbum(
      @Path("albumId") String albumId
  );

  /**
   * Get an image's details from Imgur. This is also a paid API.
   */
  @CheckResult
  @GET("https://api.imgur.com/3/image/{imageId}")
  @Headers({ HEADER_IMGUR_AUTH })
  Single<Response<ImgurImageResponse>> imgurImage(
      @Path("imageId") String imageId
  );

  @CheckResult
  @Multipart
  @POST("https://api.imgur.com/3/image")
  @Headers({ HEADER_IMGUR_AUTH })
  Single<Response<ImgurUploadResponse>> uploadToImgur(
      @Part MultipartBody.Part file,
      @Query("type") String fileType
  );

// ======== STREAMABLE ======== //

  /**
   * Get downloadable video URLs from a streamable.com link.
   */
  @CheckResult
  @GET("https://api.streamable.com/videos/{videoId}")
  Single<StreamableVideoResponse> streamableVideoDetails(
      @Path("videoId") String videoId
  );

// ======== GIPHY ======== //

  @CheckResult
  @GET("https://api.giphy.com/v1/gifs/search")
  Single<GiphySearchResponse> giphySearch(
      @Query("api_key") String apiKey,
      @Query("q") String searchQuery,
      @Query("limit") int itemsPerPage,
      @Query("offset") int paginationOffset
  );

  @CheckResult
  @GET("https://api.giphy.com/v1/gifs/trending")
  Single<GiphySearchResponse> giphyTrending(
      @Query("api_key") String apiKey,
      @Query("limit") int itemsPerPage,
      @Query("offset") int paginationOffset
  );

// ======== REDGIFS ======== //

  @CheckResult
  @GET("https://api.redgifs.com/v2/oauth/token?grant_type=client_credentials")
  @Headers({"Accept: application/json,text/html"}) // fails with 400 if text/html is not present
  Single<GfycatOauthResponse> redgifsOAuth(
      @Query("client_id") String clientId,
      @Query("client_secret") String clientSecret
  );

  @GET("https://api.redgifs.com/v2/gifs/{gfyid}")
  Single<RedgifsResponse> redgifs_no_auth(
      @Path("gfyid") String threeWordId
  );

  @GET("https://api.redgifs.com/v2/gifs/{gfyid}")
  Single<RedgifsResponse> redgifs_with_auth(
      @Header("Authorization") String authHeader,
      @Path("gfyid") String threeWordId
  );

// ======== GFYCAT ======== //

  @GET("https://api.gfycat.com/v1/gfycats/{gfyid}")
  Single<GfycatResponse> gfycat_no_auth(
      @Path("gfyid") String threeWordId
  );

  @GET("https://api.gfycat.com/v1/gfycats/{gfyid}")
  Single<GfycatResponse> gfycat_with_auth(
      @Header("Authorization") String authHeader,
      @Path("gfyid") String threeWordId
  );
}
