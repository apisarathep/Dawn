package me.saket.dank.ui.submission;

import static io.reactivex.android.schedulers.AndroidSchedulers.mainThread;
import static me.saket.dank.utils.CommonUtils.findOptimizedImage;
import static me.saket.dank.utils.RxUtils.applySchedulersSingle;
import static me.saket.dank.utils.RxUtils.doNothing;
import static me.saket.dank.utils.RxUtils.doOnStartAndEndSingle;
import static me.saket.dank.utils.Views.executeOnMeasure;
import static me.saket.dank.utils.Views.setHeight;
import static me.saket.dank.utils.Views.setMarginTop;
import static me.saket.dank.utils.Views.statusBarHeight;
import static me.saket.dank.utils.Views.touchLiesOn;

import android.annotation.SuppressLint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.alexvasilkov.gestures.GestureController;
import com.alexvasilkov.gestures.State;
import com.devbrackets.android.exomedia.ui.widget.VideoView;
import com.fasterxml.jackson.databind.JsonNode;

import net.dean.jraw.models.Submission;

import java.util.LinkedList;
import java.util.List;

import butterknife.BindDimen;
import butterknife.BindDrawable;
import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import me.saket.dank.R;
import me.saket.dank.data.Link;
import me.saket.dank.data.MediaLink;
import me.saket.dank.data.RedditLink;
import me.saket.dank.data.exceptions.ImgurApiRateLimitReachedException;
import me.saket.dank.di.Dank;
import me.saket.dank.ui.DankFragment;
import me.saket.dank.ui.OpenUrlActivity;
import me.saket.dank.ui.subreddits.SubredditActivity;
import me.saket.dank.utils.DankLinkMovementMethod;
import me.saket.dank.utils.DankSubmissionRequest;
import me.saket.dank.utils.ExoPlayerManager;
import me.saket.dank.utils.Function0;
import me.saket.dank.utils.Markdown;
import me.saket.dank.utils.UrlParser;
import me.saket.dank.utils.Views;
import me.saket.dank.widgets.AnimatedProgressBar;
import me.saket.dank.widgets.AnimatedToolbarBackground;
import me.saket.dank.widgets.InboxUI.ExpandablePageLayout;
import me.saket.dank.widgets.ScrollingRecyclerViewSheet;
import me.saket.dank.widgets.ZoomableImageView;
import timber.log.Timber;

@SuppressLint("SetJavaScriptEnabled")
public class SubmissionFragment extends DankFragment implements ExpandablePageLayout.Callbacks, ExpandablePageLayout.OnPullToCollapseIntercepter {

    private static final String KEY_SUBMISSION_JSON = "submissionJson";
    private static final String KEY_SUBMISSION_REQUEST = "submissionRequest";

    @BindView(R.id.toolbar) Toolbar toolbar;
    @BindView(R.id.submission_toolbar_shadow) View toolbarShadows;
    @BindView(R.id.submission_toolbar_background) AnimatedToolbarBackground toolbarBackground;
    @BindView(R.id.submission_content_progress_bar) AnimatedProgressBar contentLoadProgressView;
    @BindView(R.id.submission_image) ZoomableImageView contentImageView;
    @BindView(R.id.submission_image_scroll_hint) View contentImageScrollHintView;
    @BindView(R.id.submission_video_container) ViewGroup contentVideoViewContainer;
    @BindView(R.id.submission_video) VideoView contentVideoView;
    @BindView(R.id.submission_comment_list_parent_sheet) ScrollingRecyclerViewSheet commentListParentSheet;
    @BindView(R.id.submission_comments_header) ViewGroup commentsHeaderView;
    @BindView(R.id.submission_title) TextView titleView;
    @BindView(R.id.submission_subtitle) TextView subtitleView;
    @BindView(R.id.submission_selfpost_text) TextView selfPostTextView;
    @BindView(R.id.submission_link_container) ViewGroup linkDetailsView;
    @BindView(R.id.submission_comment_list) RecyclerView commentList;
    @BindView(R.id.submission_comments_progress) View commentsLoadProgressView;

    @BindDrawable(R.drawable.ic_toolbar_close_24dp) Drawable closeIconDrawable;
    @BindDimen(R.dimen.submission_commentssheet_minimum_visible_height) int commentsSheetMinimumVisibleHeight;

    private ExpandablePageLayout submissionPageLayout;
    private CommentsAdapter commentsAdapter;
    private CompositeDisposable onCollapseSubscriptions = new CompositeDisposable();
    private CommentsHelper commentsHelper;
    private Submission activeSubmission;
    private DankSubmissionRequest activeSubmissionRequest;
    private List<Runnable> pendingOnExpandRunnables = new LinkedList<>();
    private SubmissionLinkHolder linkDetailsViewHolder;
    private Link activeSubmissionContentLink;

    private SubmissionVideoHolder contentVideoViewHolder;
    private SubmissionImageHolder contentImageViewHolder;

    private int deviceDisplayWidth;
    private boolean isCommentSheetBeneathImage;

    public interface Callbacks {
        void onClickSubmissionToolbarUp();
    }

    public static SubmissionFragment create() {
        return new SubmissionFragment();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        View fragmentLayout = inflater.inflate(R.layout.fragment_submission, container, false);
        ButterKnife.bind(this, fragmentLayout);

        int statusBarHeight = statusBarHeight(getResources());
        setMarginTop(toolbar, statusBarHeight);
        executeOnMeasure(toolbar, () -> setHeight(toolbarBackground, toolbar.getHeight() + statusBarHeight));
        toolbar.setNavigationOnClickListener(v -> ((Callbacks) getActivity()).onClickSubmissionToolbarUp());

        DankLinkMovementMethod linkMovementMethod = DankLinkMovementMethod.newInstance();
        linkMovementMethod.setOnLinkClickListener((textView, url) -> {
            // TODO: 18/03/17 Remove try/catch block
            try {
                Link parsedLink = UrlParser.parse(url);
                Point clickedUrlCoordinates = linkMovementMethod.getLastUrlClickCoordinates();
                Rect clickedUrlCoordinatesRect = new Rect(0, clickedUrlCoordinates.y, deviceDisplayWidth, clickedUrlCoordinates.y);
                OpenUrlActivity.handle(getActivity(), parsedLink, clickedUrlCoordinatesRect);
                return true;

            } catch (Exception e) {
                Timber.i(e, "Couldn't parse URL: %s", url);
                return false;
            }
        });
        selfPostTextView.setMovementMethod(linkMovementMethod);

        // TODO: 01/02/17 Should we preload Views for adapter rows?
        // Setup comment list and its adapter.
        commentsAdapter = new CommentsAdapter(getResources(), linkMovementMethod);
        commentList.setAdapter(RecyclerAdapterWithHeader.wrap(commentsAdapter, commentsHeaderView));
        commentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        commentList.setItemAnimator(new DefaultItemAnimator());

        // Get the display width, that will be used in populateUi() for loading an optimized image for the user.
        deviceDisplayWidth = fragmentLayout.getResources().getDisplayMetrics().widthPixels;

        return fragmentLayout;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        submissionPageLayout = ((ExpandablePageLayout) view.getParent());
        submissionPageLayout.addCallbacks(this);
        submissionPageLayout.setPullToCollapseIntercepter(this);

        setupCommentsHelper();
        setupContentImageView(view);
        setupContentVideoView(view);
        setupCommentsSheet();

        linkDetailsViewHolder = new SubmissionLinkHolder(linkDetailsView, submissionPageLayout);

        // Restore submission if the Activity was recreated.
        if (savedInstanceState != null) {
            onRestoreSavedInstanceState(savedInstanceState);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (activeSubmission != null) {
            outState.putString(KEY_SUBMISSION_JSON, Dank.jackson().toJson(activeSubmission.getDataNode()));
            outState.putParcelable(KEY_SUBMISSION_REQUEST, activeSubmissionRequest);
        }
        super.onSaveInstanceState(outState);
    }

    private void onRestoreSavedInstanceState(Bundle savedInstanceState) {
        if (savedInstanceState.containsKey(KEY_SUBMISSION_JSON)) {
            JsonNode jsonNode = Dank.jackson().fromJson(savedInstanceState.getString(KEY_SUBMISSION_JSON));
            if (jsonNode != null) {
                populateUi(new Submission(jsonNode), savedInstanceState.getParcelable(KEY_SUBMISSION_REQUEST));
            }
        }
    }

    /**
     * {@link CommentsHelper} helps in collapsing comments and helping {@link CommentsAdapter} in indicating
     * progress when more comments are being fetched for a CommentNode.
     * <p>
     * The direction of modifications/updates to comments is unidirectional. All mods are made on
     * {@link CommentsHelper} and {@link CommentsAdapter} subscribes to its updates.
     */
    private void setupCommentsHelper() {
        commentsHelper = new CommentsHelper();

        unsubscribeOnDestroy(
                commentsHelper.updates().observeOn(mainThread()).subscribe(commentsAdapter)
        );

        // Comment clicks.
        unsubscribeOnDestroy(
                commentsAdapter.commentClicks().subscribe(commentsHelper.toggleCollapse())
        );

        // Load-more-comment clicks.
        unsubscribeOnDestroy(
                // Using an Rx chain ensures that multiple load-more-clicks are executed sequentially.
                commentsAdapter
                        .loadMoreCommentsClicks()
                        .flatMap(loadMoreClickEvent -> {
                            if (loadMoreClickEvent.parentCommentNode.isThreadContinuation()) {
                                DankSubmissionRequest continueThreadRequest = activeSubmissionRequest.toBuilder()
                                        .focusComment(loadMoreClickEvent.parentCommentNode.getComment().getId())
                                        .build();
                                Rect expandFromShape = Views.globalVisibleRect(loadMoreClickEvent.loadMoreItemView);
                                expandFromShape.top = expandFromShape.bottom;   // Because only expanding from a line is supported so far.
                                SubmissionFragmentActivity.start(getContext(), continueThreadRequest, expandFromShape);

                                return Observable.empty();

                            } else {
                                return Observable.just(loadMoreClickEvent.parentCommentNode)
                                        .observeOn(Schedulers.io())
                                        .doOnNext(commentsHelper.setMoreCommentsLoading(true))
                                        .map(Dank.reddit().loadMoreComments())
                                        .doOnNext(commentsHelper.setMoreCommentsLoading(false));
                            }
                        })
                        .subscribe(doNothing(), error -> {
                            Timber.e(error, "Failed to load more comments");
                            if (isAdded()) {
                                Toast.makeText(getActivity(), R.string.submission_error_failed_to_load_more_comments, Toast.LENGTH_SHORT).show();
                            }
                        })
        );
    }

    private void setupContentImageView(View fragmentLayout) {
        Views.setMarginBottom(contentImageView, commentsSheetMinimumVisibleHeight);

        contentImageViewHolder = new SubmissionImageHolder(fragmentLayout, contentLoadProgressView, submissionPageLayout, deviceDisplayWidth);
        contentImageViewHolder.setup();
    }

    private void setupContentVideoView(View fragmentLayout) {
        Views.setMarginBottom(contentVideoViewContainer, commentsSheetMinimumVisibleHeight);

        ExoPlayerManager exoPlayerManager = ExoPlayerManager.newInstance(this, contentVideoView);
        contentVideoViewHolder = new SubmissionVideoHolder(fragmentLayout, contentLoadProgressView, submissionPageLayout, exoPlayerManager);
        contentVideoViewHolder.setup();
    }

    private void setupCommentsSheet() {
        toolbarBackground.syncPositionWithSheet(commentListParentSheet);
        commentListParentSheet.setScrollingEnabled(false);

        Function0<Integer> mediaRevealDistanceFunc = () -> {
            // If the sheet cannot scroll up because the top-margin > sheet's peek distance, scroll it to 70%
            // of its height so that the user doesn't get confused upon not seeing the sheet scroll up.
            float mediaVisibleHeight = activeSubmissionContentLink.isImageOrGif()
                    ? contentImageView.getVisibleZoomedImageHeight()
                    : contentVideoViewContainer.getHeight();

            return (int) Math.min(
                    commentListParentSheet.getHeight() * 8 / 10,
                    mediaVisibleHeight - commentListParentSheet.getTop()
            );
        };

        // Toggle sheet's collapsed state on image click.
        contentImageView.setOnClickListener(v -> {
            commentListParentSheet.smoothScrollTo(mediaRevealDistanceFunc.calculate());
        });

        // and on submission title click.
        commentsHeaderView.setOnClickListener(v -> {
            if (activeSubmissionContentLink instanceof MediaLink && commentListParentSheet.isAtPeekHeightState()) {
                commentListParentSheet.smoothScrollTo(mediaRevealDistanceFunc.calculate());
            }
        });

        // Calculates if the top of the comment sheet is directly below the image.
        Function0<Boolean> isCommentSheetBeneathImageFunc = () -> {
            //noinspection CodeBlock2Expr
            return (int) commentListParentSheet.getY() == (int) contentImageView.getVisibleZoomedImageHeight();
        };

        // Keep the comments sheet always beneath the image.
        contentImageView.getController().addOnStateChangeListener(new GestureController.OnStateChangeListener() {
            float lastZoom = contentImageView.getZoom();

            @Override
            public void onStateChanged(State state) {
                if (contentImageView.getDrawable() == null) {
                    // Image isn't present yet. Ignore.
                    return;
                }
                boolean isZoomingOut = lastZoom > state.getZoom();
                lastZoom = state.getZoom();

                int boundedVisibleImageHeight = (int) Math.min(contentImageView.getHeight(), contentImageView.getVisibleZoomedImageHeight());
                int imageRevealDistance = boundedVisibleImageHeight - commentListParentSheet.getTop();
                commentListParentSheet.setPeekHeight(commentListParentSheet.getHeight() - imageRevealDistance);

                if (isCommentSheetBeneathImage
                        // This is a hacky workaround: when zooming out, the received callbacks are very discrete and
                        // it becomes difficult to lock the comments sheet beneath the image.
                        || (isZoomingOut && contentImageView.getVisibleZoomedImageHeight() <= commentListParentSheet.getY()))
                {
                    commentListParentSheet.scrollTo(imageRevealDistance);
                }
                isCommentSheetBeneathImage = isCommentSheetBeneathImageFunc.calculate();
            }

            @Override
            public void onStateReset(State oldState, State newState) {

            }
        });

        commentListParentSheet.addOnSheetScrollChangeListener(newScrollY -> {
            isCommentSheetBeneathImage = isCommentSheetBeneathImageFunc.calculate();
        });
    }

    /**
     * Update the submission to be shown. Since this Fragment is retained by {@link SubredditActivity},
     * we only update the UI everytime a new submission is to be shown.
     *
     * @param submissionRequest used for loading the comments of this submission.
     */
    public void populateUi(Submission submission, DankSubmissionRequest submissionRequest) {
        activeSubmission = submission;
        activeSubmissionRequest = submissionRequest;

        // Reset everything.
        contentLoadProgressView.setProgress(0);
        commentListParentSheet.scrollTo(0);
        commentListParentSheet.setScrollingEnabled(false);
        commentsHelper.reset();
        commentsAdapter.updateData(null);

        // Update submission information.
        //noinspection deprecation
        titleView.setText(Html.fromHtml(submission.getTitle()));
        subtitleView.setText(getString(R.string.subreddit_name_r_prefix, submission.getSubredditName()));

        // Load self-text/media/webpage.
        Link contentLink = UrlParser.parse(submission.getUrl(), submission.getThumbnails());
        loadSubmissionContent(submission, contentLink);

        // Load new comments.
        if (submission.getComments() == null) {
            unsubscribeOnCollapse(Dank.reddit()
                    .submission(activeSubmissionRequest)
                    .flatMap(retryWithCorrectSortIfNeeded())
                    .compose(applySchedulersSingle())
                    .compose(doOnStartAndEndSingle(start -> commentsLoadProgressView.setVisibility(start ? View.VISIBLE : View.GONE)))
                    .doOnSuccess(submWithComments -> activeSubmission = submWithComments)
                    .subscribe(commentsHelper.setup(), handleSubmissionLoadError())
            );

        } else {
            commentsHelper.setup().accept(submission);
        }
    }

    /**
     * The aim is to always load comments in the sort mode suggested by a subreddit. In case we accidentally
     * load in the wrong mode (maybe because the submission's details were unknown), this function reloads
     * the submission's data using its suggested sort.
     */
    @NonNull
    private Function<Submission, Single<Submission>> retryWithCorrectSortIfNeeded() {
        return submWithComments -> {
            if (submWithComments.getSuggestedSort() != null && submWithComments.getSuggestedSort() != activeSubmissionRequest.commentSort()) {
                activeSubmissionRequest = activeSubmissionRequest.toBuilder()
                        .commentSort(submWithComments.getSuggestedSort())
                        .build();
                return Dank.reddit().submission(activeSubmissionRequest);

            } else {
                return Single.just(submWithComments);
            }
        };
    }

    public Consumer<Throwable> handleSubmissionLoadError() {
        return error -> Timber.e(error, error.getMessage());
    }

    private void loadSubmissionContent(Submission submission, Link contentLink) {
        activeSubmissionContentLink = contentLink;

//        Timber.d("-------------------------------------------");
//        Timber.i("%s", submission.getTitle());
//        Timber.i("Post hint: %s, URL: %s", submission.getPostHint(), submission.getUrl());
//        Timber.i("Parsed content: %s, type: %s", contentLink, contentLink.type());
//        if (submissionContent.type() == SubmissionContent.Type.IMAGE) {
//            Timber.i("Optimized image: %s", submissionContent.imageContentUrl(deviceDisplayWidth));
//        }

        boolean isImgurAlbum = contentLink instanceof MediaLink.ImgurAlbum;
        linkDetailsViewHolder.setVisible(!isImgurAlbum && contentLink.isExternal() || contentLink.isRedditHosted() && !submission.isSelfPost());
        selfPostTextView.setVisibility(submission.isSelfPost() ? View.VISIBLE : View.GONE);
        contentImageView.setVisibility(contentLink.isImageOrGif() ? View.VISIBLE : View.GONE);
        contentVideoViewContainer.setVisibility(contentLink.isVideo() ? View.VISIBLE : View.GONE);

        // Show shadows behind the toolbar because image/video submissions have a transparent toolbar.
        boolean transparentToolbar = contentLink.isImageOrGif() || contentLink.isVideo();
        toolbarBackground.setSyncScrollEnabled(transparentToolbar);
        toolbarShadows.setVisibility(transparentToolbar ? View.VISIBLE : View.GONE);

        if (contentLink instanceof MediaLink.ImgurUnresolvedGallery) {
            contentLoadProgressView.show();
            String redditSuppliedThumbnail = findOptimizedImage(activeSubmission.getThumbnails(), linkDetailsViewHolder.getThumbnailWidthForAlbum());

            unsubscribeOnCollapse(Dank.imgur()
                    .gallery((MediaLink.ImgurUnresolvedGallery) contentLink)
                    .compose(applySchedulersSingle())
                    .subscribe(imgurResponse -> {
                        if (imgurResponse.isAlbum()) {
                            String coverImageUrl;
                            if (redditSuppliedThumbnail != null) {
                                coverImageUrl = redditSuppliedThumbnail;
                            } else {
                                coverImageUrl = imgurResponse.images().get(0).url();
                            }

                            String albumUrl = ((MediaLink.ImgurUnresolvedGallery) contentLink).albumUrl();
                            int imageCount = imgurResponse.images().size();
                            MediaLink.ImgurAlbum albumLink = MediaLink.ImgurAlbum.create(albumUrl, imgurResponse.albumTitle(), coverImageUrl, imageCount);
                            loadSubmissionContent(submission, albumLink);

                        } else {
                            Link coverImageLink = UrlParser.parse(imgurResponse.images().get(0).url(), submission.getThumbnails());
                            loadSubmissionContent(submission, coverImageLink);
                        }

                    }, error -> {
                        // Open this album in browser if Imgur rate limits have reached.
                        if (error instanceof ImgurApiRateLimitReachedException) {
                            String albumUrl = ((MediaLink.ImgurUnresolvedGallery) contentLink).albumUrl();
                            loadSubmissionContent(submission, Link.External.create(albumUrl));

                        } else {
                            // TODO: 05/04/17 Handle errors (including InvalidImgurAlbumException).
                            Toast.makeText(getContext(), "Couldn't load image", Toast.LENGTH_SHORT).show();
                            Timber.e(error, "Couldn't load album cover image");
                            contentLoadProgressView.hide();
                        }
                    }));
            return;
        }

        switch (contentLink.type()) {
            case IMAGE_OR_GIF:
                contentImageViewHolder.load((MediaLink) contentLink);
                break;

            case REDDIT_HOSTED:
                if (submission.isSelfPost()) {
                    contentLoadProgressView.hide();
                    String selfTextHtml = submission.getDataNode().get("selftext_html").asText("");
                    CharSequence markdownHtml = Markdown.parseRedditMarkdownHtml(selfTextHtml, selfPostTextView.getPaint());
                    selfPostTextView.setVisibility(markdownHtml.length() > 0 ? View.VISIBLE : View.GONE);
                    selfPostTextView.setText(markdownHtml);

                } else {
                    contentLoadProgressView.hide();
                    //noinspection ConstantConditions
                    unsubscribeOnCollapse(linkDetailsViewHolder.populate(((RedditLink) contentLink)));
                    linkDetailsView.setOnClickListener(__ -> OpenUrlActivity.handle(getContext(), contentLink, null));
                }
                break;

            case EXTERNAL:
                contentLoadProgressView.hide();
                String redditSuppliedThumbnail = findOptimizedImage(
                        activeSubmission.getThumbnails(),
                        linkDetailsViewHolder.thumbnailWidthForExternalLink()
                );
                linkDetailsView.setOnClickListener(__ -> OpenUrlActivity.handle(getContext(), contentLink, null));

                if (isImgurAlbum) {
                    linkDetailsViewHolder.populate(((MediaLink.ImgurAlbum) contentLink), redditSuppliedThumbnail);
                } else {
                    unsubscribeOnCollapse(linkDetailsViewHolder.populate(((Link.External) contentLink), redditSuppliedThumbnail));
                }
                break;

            case VIDEO:
                unsubscribeOnCollapse(contentVideoViewHolder.load((MediaLink) contentLink));
                break;

            default:
                throw new UnsupportedOperationException("Unknown content: " + contentLink);
        }
    }

// ======== EXPANDABLE PAGE CALLBACKS ======== //

    /**
     * @param upwardPagePull True if the PAGE is being pulled upwards. Remember that upward pull == downward scroll and vice versa.
     * @return True to consume this touch event. False otherwise.
     */
    @Override
    public boolean onInterceptPullToCollapseGesture(MotionEvent event, float downX, float downY, boolean upwardPagePull) {
        if (touchLiesOn(commentListParentSheet, downX, downY)) {
            return upwardPagePull
                    ? commentListParentSheet.canScrollUpwardsAnyFurther()
                    : commentListParentSheet.canScrollDownwardsAnyFurther();
        } else {
            return touchLiesOn(contentImageView, downX, downY) && contentImageView.canPanVertically(!upwardPagePull);
        }
    }

    @Override
    public void onPageAboutToExpand(long expandAnimDuration) {
    }

    @Override
    public void onPageExpanded() {
        for (Runnable runnable : pendingOnExpandRunnables) {
            runnable.run();
            pendingOnExpandRunnables.remove(runnable);
        }
    }

    @Override
    public void onPageAboutToCollapse(long collapseAnimDuration) {
    }

    @Override
    public void onPageCollapsed() {
        contentVideoViewHolder.pausePlayback();
        onCollapseSubscriptions.clear();
    }

    private void unsubscribeOnCollapse(Disposable subscription) {
        onCollapseSubscriptions.add(subscription);
        unsubscribeOnDestroy(subscription);
    }

}
