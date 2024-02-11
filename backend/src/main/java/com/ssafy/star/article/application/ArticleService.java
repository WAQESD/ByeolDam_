package com.ssafy.star.article.application;

import com.ssafy.star.article.DisclosureType;
import com.ssafy.star.article.dao.ArticleHashtagRelationRepository;
import com.ssafy.star.article.dao.ArticleLikeRepository;
import com.ssafy.star.article.dao.ArticleRepository;
import com.ssafy.star.article.domain.ArticleEntity;
import com.ssafy.star.article.domain.ArticleHashtagRelationEntity;
import com.ssafy.star.article.domain.ArticleLikeEntity;
import com.ssafy.star.article.dto.Article;
import com.ssafy.star.common.exception.ByeolDamException;
import com.ssafy.star.common.exception.ErrorCode;
import com.ssafy.star.common.infra.S3.S3uploader;
import com.ssafy.star.constellation.SharedType;
import com.ssafy.star.constellation.dao.ConstellationRepository;
import com.ssafy.star.constellation.domain.ConstellationEntity;
import com.ssafy.star.image.ImageType;
import com.ssafy.star.image.application.ImageService;
import com.ssafy.star.image.domain.ImageEntity;
import com.ssafy.star.user.domain.FollowEntity;
import com.ssafy.star.user.domain.UserEntity;
import com.ssafy.star.user.dto.User;
import com.ssafy.star.user.repository.FollowRepository;
import com.ssafy.star.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final ArticleHashtagRelationRepository articleHashtagRelationRepository;
    private final ConstellationRepository constellationRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final S3uploader s3uploader;
    private final ImageService imageService;
    private final ArticleHashtagRelationService articleHashtagRelationService;
    private final ArticleLikeRepository articleLikeRepository;

    // 트랜잭션 처리를 하고 롤백 처리를 하려면 controller가 아니라 서비스단에서 upload를 호출해야할듯하다
    // try catch문을 통해서 사진 업로드 이후 save를 하고
    // 문제가 발생했을 경우(게시글이 정상적으로 생성되지 않았을 경우)
    // 롤백을 해준다 (문제가 발생했으면 앞서 저장된 이미지 삭제

    /**
     * 게시물 등록
      */
    @Transactional
    public void create(
            String title,
            String description,
            DisclosureType disclosureType,
            String email,
            MultipartFile imageFile,
            ImageType imageType,
            Set<String> articleHashtagSet
    ) {
        String url = "";
        String thumbnailUrl = "";
        UserEntity userEntity = getUserEntityOrException(email);

        try {
            url = s3uploader.upload(imageFile, "articles");
            thumbnailUrl = s3uploader.uploadThumbnail(imageFile, "thumbnails");
            ImageEntity imageEntity = imageService.saveImage(imageFile.getOriginalFilename(), url, thumbnailUrl, imageType);

            ArticleEntity articleEntity = ArticleEntity.of(title,
                    description,
                    disclosureType,
                    userEntity,
                    null,
                    imageEntity
            );

            articleRepository.save(articleEntity);

            articleHashtagRelationService.saveHashtag(articleEntity, articleHashtagSet);
            // TODO :articleHashtagRepository에서 String이 같은 것이 있다면 추가 X, 없다면 추가 O
            // => 해시태그 tagName 속성은 unique
        } catch (IOException e) {
            s3uploader.deleteImageFromS3(url);
            s3uploader.deleteImageFromS3(thumbnailUrl);
        }
    }

    /**
     * 게시물 수정
     */
    @Transactional
    public void modify(
            Long articleId,
            String title,
            String description,
            DisclosureType disclosure,
            String email,
            Set<String> articleHashtagSet
    ) {
        // 게시물 owner가 맞는지 확인
        ArticleEntity articleEntity = getArticleOwnerOrException(articleId, email);

        articleHashtagRelationService.deleteByArticleEntity(articleEntity);
        articleHashtagRelationService.saveHashtag(articleEntity, articleHashtagSet);

        articleEntity.update(title, description, disclosure);
    }

    /**
     * 게시물 삭제
     */
    @Transactional
    public void delete(Long articleId, String email) {
        // 게시물 owner가 맞는지 확인
        ArticleEntity articleEntity = getArticleOwnerOrException(articleId, email);
        if(articleEntity.getDeletedAt() != null) {
            throw new ByeolDamException(ErrorCode.ARTICLE_DELETED, String.format("article %s has already deleted", articleId));
        } else {
            System.out.println("deletedAt : " + String.valueOf(articleEntity.getDeletedAt()));
            articleLikeRepository.deleteAllByArticleEntity(articleEntity);
            articleRepository.delete(articleEntity);
            articleHashtagRelationService.deleteByArticleEntity(articleEntity);
        }
    }

    /**
     * 휴지통 조회
     */
    @Transactional
    public Page<Article> trashcan(String email, Pageable pageable) {
        UserEntity userEntity = getUserEntityOrException(email);
        return articleRepository.findAllByOwnerEntityAndDeleted(userEntity, pageable).map(Article::fromEntity);
    }

    /**
     * 휴지통에 있는 게시물 복원
     */
    @Transactional
    public Article undoDeletion(Long articleId, String email) {
        ArticleEntity articleEntity = getArticleEntityOrException(articleId);
        UserEntity userEntity = getUserEntityOrException(email);

        if(!articleEntity.getOwnerEntity().equals(userEntity)) {
            throw new ByeolDamException(ErrorCode.INVALID_PERMISSION, String.format("%s has no permission", "email:" + email));
        }

        if(articleEntity.getDeletedAt() != null) {
            System.out.println("deletedAt : " + String.valueOf(articleEntity.getDeletedAt()));
            articleEntity.undoDeletion();
            articleLikeRepository.findAllByArticleEntity(articleEntity).forEach(ArticleLikeEntity::undoDeletion);
            articleHashtagRelationRepository.findAllByArticleEntity(articleEntity).forEach(ArticleHashtagRelationEntity::undoDeletion);

        } else {
            throw new ByeolDamException(ErrorCode.INVALID_REQUEST, String.format("%s is not abandoned", "articleId:" + Long.toString(articleId)));
        }

        return Article.fromEntity(articleEntity);
    }

    /**
     * 팔로우 피드
     */
    // 팔로우한 사람들의 게시물들을 최신순으로 나열해서 보여준다
    @Transactional(readOnly = true)
    public Page<Article> followFeed(String email, Pageable pageable) {
        UserEntity userEntity = getUserEntityOrException(email);
        Set<UserEntity> userEntitySet = userEntity.getFollowEntities().stream().map(FollowEntity::getToUser).collect(Collectors.toSet());

        List<ArticleEntity> articleEntityList = new ArrayList<>();
        // ID별 모든 게시글 가져오기
        for(UserEntity tmpUserEntity : userEntitySet) {
            List<ArticleEntity> tmpArticleEntities = tmpUserEntity.getArticleEntities();
            articleEntityList.addAll(tmpArticleEntities);
        }

        // 최신순 정렬
        Collections.sort(articleEntityList, new Comparator<ArticleEntity>() {
            @Override
            public int compare(ArticleEntity a1, ArticleEntity a2) {
                return a2.getCreatedAt().compareTo(a1.getCreatedAt());
            }
        });

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), articleEntityList.size());
        List<Article> dtoList = articleEntityList.subList(start, end)
                .stream()
                .map(Article::fromEntity)
                .collect(Collectors.toList());

        // 페이징처리
        return new PageImpl<>(dtoList, pageable, articleEntityList.size());
    }

    /**
     * 게시물 전체 조회
     */
    @Transactional(readOnly = true)
    public Page<Article> list(String email, Pageable pageable) {
        UserEntity userEntity = getUserEntityOrException(email);
        // Not deleted 상태이고, DisclosureType이 VISIBLE이거나 자신의 게시물이라면 보여주기
        return articleRepository.findAllByNotDeletedAndDisclosure(userEntity, pageable).map(Article::fromEntity);
    }

    /**
     * 유저의 게시물 전체 조회
     */
    @Transactional(readOnly = true)
    public Page<Article> userArticlePage(String userEmail, String myEmail, Pageable pageable) {
        // 찾는 유저가 접속자라면 전체 조회한다
        UserEntity myEntity = getUserEntityOrException(myEmail);
        UserEntity userEntity = getUserEntityOrException(userEmail);
        if(!myEntity.equals(userEntity)) {

            // following 중이라면 전체 조회한다
            if(!followRepository.findByFromUserAndToUser(myEntity, userEntity).isPresent()) {

                // disclosureType에 따라 조회여부 판단
                return articleRepository.findAllByOwnerEntityAndNotDeletedAndDisclosure(userEntity, pageable).map(Article::fromEntity);
            }
        }
        return articleRepository.findAllByOwnerEntityAndNotDeleted(userEntity, pageable).map(Article::fromEntity);
    }

    /**
     * 게시물 상세 조회
     */
    @Transactional(readOnly = true)
    public Article detail(Long articleId, String email) {
        UserEntity userEntity = getUserEntityOrException(email);

        // 해당 article이 없을 경우 예외처리
        ArticleEntity articleEntity = articleRepository.findById(articleId)
                .orElseThrow(() ->
                        new ByeolDamException(ErrorCode.ARTICLE_NOT_FOUND, String.format("%s not founded", "articleId:" + Long.toString(articleId))));

        // articleId AND deletedAt == null AND (내 게시물이거나 VISIBLE)
        if(articleRepository.findByArticleIdAndNotDeleted(articleId, userEntity)) {
            articleEntity.addHits();

            return Article.fromEntity(articleRepository.save(articleEntity));
        } else {
            // Deletion 예외처리
            if(articleEntity.getDeletedAt() != null) {
                throw new ByeolDamException(ErrorCode.ARTICLE_DELETED, String.format("%s deleted", "articleId:" + Long.toString(articleId)));
            }

            // 볼 수 있는 권한이 없다(내 게시물이 아니거나 INVISIBLE)
            throw new ByeolDamException(ErrorCode.INVALID_PERMISSION, String.format("%s has no permission with %s", "email:"+email, "articleId:" + Long.toString(articleId)));
        }
    }

    /**
     * 별자리 배정 및 변경
     */
    @Transactional
    public void select(Long articleId, Long constellationId, String email) {
        ArticleEntity articleEntity = getArticleOwnerOrException(articleId, email);
        ConstellationEntity constellationEntity = getConstellationEntityOrException(constellationId);

        // 선택한 별자리가 기존의 constellationEntity인 경우 Error 반환
        if(articleEntity.getConstellationEntity() == null) {

        } else if (articleEntity.getConstellationEntity().equals(constellationEntity)) {
            throw new ByeolDamException(ErrorCode.INVALID_REQUEST, String.format("%s is already constellation %s", "articleId:" + Long.toString(articleId), "constellationId:" + Long.toString(constellationId)));
        }

        articleEntity.selectConstellation(constellationEntity);
    }

    /**
     * 별자리의 전체 게시물 조회
     */
    @Transactional
    public Page<Article> articlesInConstellation(Long constellationId, String email, Pageable pageable) {
        // email로 userEntity 구하고 별자리 공개여부와 해당 게시물 공유여부를 확인해 Error 반환
        UserEntity userEntity = getUserEntityOrException(email);
        ConstellationEntity constellationEntity = getConstellationEntityOrException(constellationId);

        if(constellationEntity.getShared() == SharedType.NONSHARED) {
            throw new ByeolDamException(ErrorCode.INVALID_REQUEST, "constellation is Non-Shared");
        }

        return articleRepository.findAllByConstellationEntity(constellationEntity, userEntity, pageable).map(Article::fromEntity);
    }

    // 포스트가 존재하는지
    private ArticleEntity getArticleEntityOrException(Long articleId) {
        return articleRepository.findById(articleId).orElseThrow(() ->
                new ByeolDamException(ErrorCode.ARTICLE_NOT_FOUND, String.format("%s not founded", "articleId:" + Long.toString(articleId))));
    }

    // 유저가 존재하는지
    private UserEntity getUserEntityOrException(String email) {
        return userRepository.findByEmail(email).orElseThrow(() ->
                new ByeolDamException(ErrorCode.USER_NOT_FOUND, String.format("%s not founded", "email:" +email)));
    }

    // 별자리가 존재하는지
    private ConstellationEntity getConstellationEntityOrException(Long constellationId) {
        return constellationRepository.findById(constellationId).orElseThrow(() ->
                new ByeolDamException(ErrorCode.CONSTELLATION_NOT_FOUND, String.format("%s not founded", "constellationId:" + Long.toString(constellationId))));
    }

    // 게시물 owner인지 확인
    private ArticleEntity getArticleOwnerOrException(Long articleId, String email){
        UserEntity userEntity = getUserEntityOrException(email);                                    // 현재 사용자 user entity
        ArticleEntity articleEntity = getArticleEntityOrException(articleId);
        UserEntity ownerEntity = articleEntity.getOwnerEntity();                              // admin의 user entity

        // admin이어야 삭제 가능
        if(ownerEntity != userEntity) {
            throw new ByeolDamException(ErrorCode.INVALID_PERMISSION,
                    String.format("%s has no permission with %s", "email:"+email, "articleId:" + Long.toString(articleId)));
        }

        return articleEntity;
    }

    //게시물 좋아요 요청
    @Transactional
    public void like(Long articleId, String email) {
        ArticleEntity articleEntity = getArticleEntityOrException(articleId);
        UserEntity userEntity = getUserEntityOrException(email);

        // 좋아요 상태인지 확인
        articleLikeRepository.findByUserEntityAndArticleEntity(userEntity, articleEntity).ifPresentOrElse(
                articleLikeRepository::delete,
                () -> articleLikeRepository.save(ArticleLikeEntity.of(userEntity, articleEntity))
        );
    }

    //게시물 좋아요 상태 확인
    @Transactional
    public Boolean checkLike(Long articleId, String email) {
        ArticleEntity articleEntity = getArticleEntityOrException(articleId);
        UserEntity userEntity = getUserEntityOrException(email);

        //좋아요 상태인지 확인
        return articleLikeRepository.findByUserEntityAndArticleEntity(userEntity, articleEntity).isPresent();
    }

    //게시물 좋아요 갯수 확인
    @Transactional
    public Integer likeCount(Long articleId) {
        ArticleEntity articleEntity = getArticleEntityOrException(articleId);

        //좋아요 갯수 확인
        return articleLikeRepository.countByArticleEntity(articleEntity);
    }

    //게시물 좋아요한 사람들의 목록 확인
    @Transactional
    public List<User> likeList(Long articleId) {
        ArticleEntity articleEntity = getArticleEntityOrException(articleId);
        //목록 확인
        return articleLikeRepository.findAllByArticleEntity(articleEntity)
                .stream()
                .map(ArticleLikeEntity::getUserEntity)
                .map(User::fromEntity)
                .toList();
    }

}
