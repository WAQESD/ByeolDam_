package com.ssafy.star.constellation.application;

import com.ssafy.star.common.exception.ByeolDamException;
import com.ssafy.star.common.exception.ErrorCode;
import com.ssafy.star.constellation.ConstellationUserRole;
import com.ssafy.star.constellation.SharedType;
import com.ssafy.star.constellation.dao.ConstellationRepository;
import com.ssafy.star.constellation.dao.ConstellationUserRepository;
import com.ssafy.star.constellation.domain.ConstellationEntity;
import com.ssafy.star.constellation.domain.ConstellationUserEntity;
import com.ssafy.star.constellation.dto.Constellation;

import com.ssafy.star.constellation.dto.ConstellationUser;
import com.ssafy.star.user.domain.UserEntity;
import com.ssafy.star.user.dto.User;
import com.ssafy.star.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.ssafy.star.constellation.ConstellationUserRole.ADMIN;
import static com.ssafy.star.constellation.ConstellationUserRole.USER;

@Service
@RequiredArgsConstructor
public class ConstellationService {

    private final ConstellationRepository constellationRepository;
    private final ConstellationUserRepository constellationUserRepository;
    private final UserRepository userRepository;

    // 별자리 전체 조회
    public Page<Constellation> list(String email, Pageable pageable) {

        // 사용자 Entity
        UserEntity userEntity = getUserEntityByEmailOrException(email);

        Page<ConstellationUserEntity> constellationUserEntityPage = constellationUserRepository.findConstellationUserEntitiesByUserEntity(userEntity, pageable);

        // TODO : 공개되어 있는 별자리거나 내 별자리인 경우
        return constellationUserEntityPage.map(ConstellationUser::getConstellationEntity).map(Constellation::fromEntity);
    }

    // 유저 별자리 전체 조회
    public Page<Constellation> userConstellations(Long userId, String email, Pageable pageable) {
        UserEntity ownerEntity = getUserEntityByIdOrException(userId);

        UserEntity myEntity = getUserEntityByEmailOrException(email);

        // 찾으려는 유저가 접속자라면 전체 조회
        if(myEntity.getId() == ownerEntity.getId()) {
            return constellationRepository.findAllByUserEntity(ownerEntity, pageable).map(Constellation::fromEntity);
        }

        // TODO : 내가 속한 별자리라면 조회

        // userEntity -> constellationUserEntity
        Page<ConstellationUserEntity> constellationUserEntityPage = constellationUserRepository.findConstellationUserEntitiesByUserEntity(ownerEntity, pageable);
        Page<UserEntity> userEntityPage =constellationUserEntityPage.map(ConstellationUserEntity::getUserEntity);

        //아닐 경우 SHARED라면 조회
        // 별자리회원 Entity -> 별자리 Entity -> ConstellationDTO -> SharedType SHARED 필터링
        List<Constellation> constellations = constellationUserEntityPage
                .map(ConstellationUserEntity::getConstellationEntity)
                .map(Constellation::fromEntity)
                .filter(constellation -> constellation.shared().equals(SharedType.SHARED))
                .stream().toList();

        return new PageImpl<Constellation>(constellations);
    }

    // 별자리 상세 조회
    public Constellation detail(Long constellationId, String email){
        // 해당 constellation 없을 경우 예외처리
        ConstellationEntity constellationEntity = getConstellationEntityOrException(constellationId);

        UserEntity userEntity = getUserEntityByEmailOrException(email);
        List<ConstellationUserEntity> constellationUserEntities = constellationEntity.getConstellationUserEntities();
        for (int i = 0; i < constellationUserEntities.size(); i++) {
            // 사용자의 별자리라면 조회
            if(constellationUserEntities.get(i).getUserEntity().equals(userEntity)) {
                constellationEntity.increaseHits();
                return Constellation.fromEntity(constellationEntity);
            }
        }

        // 비공개 게시물 예외처리
        if (constellationEntity.getShared() != SharedType.SHARED) {
            throw new ByeolDamException(ErrorCode.INVALID_PERMISSION, String.format("%s has no permission with %s", email, Long.toString(constellationId)));
        }

        constellationEntity.increaseHits();
        return Constellation.fromEntity(constellationEntity);
    }


    @Transactional
    public Constellation create(String name, SharedType shared, String description, String email) {
        // 사용자의 user 엔터티 가져오기
        UserEntity userEntity = getUserEntityByEmailOrException(email);

        // 별자리 엔터티 생성
        ConstellationEntity constellationEntity = ConstellationEntity.of(
                name,
                shared,
                description
        );

        // ConstellationUserEntity 생성 및 연결
        ConstellationUserEntity constellationUserEntity = new ConstellationUserEntity(
                constellationEntity,
                userEntity,
                ConstellationUserRole.ADMIN
        );

        constellationUserRepository.save(constellationUserEntity);

        // 별자리를 데이터베이스에 저장
        ConstellationEntity savedConstellationEntity = constellationRepository.saveAndFlush(constellationEntity);

        return Constellation.fromEntity(savedConstellationEntity);
    }


    public Constellation modify(
            Long constellationId,
            String name,
            SharedType shared,
            String description,
            String email                // 사용자의 email
    ) {
        // 사용자가 admin인지 확인
        ConstellationEntity constellationEntity = getConstellationEntityIfAdminOrException(constellationId, email);

        if(name != null) {
            constellationEntity.setName(name);
        }
        if(shared != null) {
            constellationEntity.setShared(shared);
        }
        constellationEntity.setDescription(description);    // 설명 null 가능

        return Constellation.fromEntity(constellationRepository.saveAndFlush(constellationEntity));
    }


    @Transactional
    public void delete(Long constellationId, String email) {
        // 사용자가 admin인지 확인
        ConstellationEntity constellationEntity = getConstellationEntityIfAdminOrException(constellationId, email);

        constellationRepository.delete(constellationEntity);
    }

    // 별자리에 공유할 유저 추가
    public void addUser(Long constellationId, Long userId, String email) {
        // user 존재하는지 확인
        UserEntity userEntity = getUserEntityByIdOrException(userId);

        // 사용자가 admin인지 확인
        ConstellationEntity constellationEntity = getConstellationEntityIfAdminOrException(constellationId, email);

        // 별자리회원 연관관계가 이미 존재한다면
        if(constellationUserRepository.findByUserEntityAndConstellationEntity(userEntity, constellationEntity).isPresent()) {
            throw new ByeolDamException(ErrorCode.INVALID_REQUEST, String.format("%s has already added", Long.toString(userId)));
        }

        // 별자리에 공유할 유저 추가, 권한은 유저로
        ConstellationUserEntity constellationUserEntity = new ConstellationUserEntity(constellationEntity, userEntity, USER);
        constellationEntity.addUser(constellationUserEntity);

        constellationUserRepository.saveAndFlush(constellationUserEntity);
    }

    /**
     * 별자리 공유하는 유저 강퇴
     */
    @Transactional
    public void deleteUser(Long constellationId, Long userId, String email, Pageable pageable) {
        UserEntity myEntity = getUserEntityByEmailOrException(email);
        ConstellationEntity constellationEntity = getConstellationEntityOrException(constellationId);
        if(constellationEntity.getAdminEntity().getId() != myEntity.getId()) {
            // 접속자가 별자리 admin이 아니라면
            throw new ByeolDamException(ErrorCode.INVALID_PERMISSION, String.format("%s has no permission", email));
        }

        // Admin 본인 별자리회원 연관관계는 삭제 불가
        UserEntity userEntity = getUserEntityByIdOrException(userId);
        if(myEntity.equals(userEntity)) {
            throw new ByeolDamException(ErrorCode.INVALID_REQUEST, String.format("you cannot delete yourself"));
        }

        // userEntity가 constellationEntity에 속하는지
        Page<ConstellationUserEntity> constellationUserEntityPage = constellationUserRepository.findConstellationUserEntitiesByConstellationEntity(constellationEntity, pageable);
        Page<UserEntity> userEntityPage = constellationUserEntityPage.map(ConstellationUserEntity::getUserEntity);
        int idx = 0;
        for(UserEntity tmp : userEntityPage) {
            if(tmp.equals(userEntity)) {
                break;
            } else {
                if(++idx == userEntityPage.getTotalElements()) {
                    throw new ByeolDamException(ErrorCode.INVALID_REQUEST, String.format("%s not belongs to %s", "userId:"+Long.toString(userId), "constellationId:"+Long.toString(constellationId)));
                }
            }
        }

        // 삭제
        ConstellationUserEntity constellationUserEntity = constellationUserRepository.findByUserEntityAndConstellationEntity(userEntity, constellationEntity)
                .orElseThrow(() ->
                        new ByeolDamException(ErrorCode.INVALID_REQUEST, "wrong gateway"));
        constellationEntity.deleteUser(constellationUserEntity);

        constellationUserRepository.saveAndFlush(constellationUserEntity);
    }

    /**
     * 별자리 공유하는 유저 조회
     * 공개된 별자리는 모두가 유저 조회할 수 있고, 비공개 별자리는 회원들만 유저 조회 가능
     */
    public Page<User> findSharedUsers(Long constellationId, String email, Pageable pageable) {
        ConstellationEntity constellationEntity = getConstellationEntityOrException(constellationId);

        // 별자리에 속한 user 구하기 : constellationEntity -> constellationUserEntity -> userEntity
        Page<ConstellationUserEntity> constellationUserPageByConstellationEntity = constellationUserRepository.findConstellationUserEntitiesByConstellationEntity(constellationEntity, pageable);
        Page<UserEntity> userEntityPage = constellationUserPageByConstellationEntity.map(ConstellationUserEntity::getUserEntity);

        if(constellationEntity.getShared().equals(SharedType.SHARED)) {
            // 공개된 별자리
        } else {
            // 비공개된 별자리
            UserEntity userEntity = getUserEntityByEmailOrException(email);

            // 접속자가 소유하고 있는 별자리 구하기 : userEntity -> ConstellationUserEntity -> constellationEntity
            Page<ConstellationUserEntity> constellationUserPageByUserEntity = constellationUserRepository.findConstellationUserEntitiesByUserEntity(userEntity, pageable);
            Page<ConstellationEntity> constellationEntities = constellationUserPageByUserEntity.map(ConstellationUserEntity::getConstellationEntity);

            int idx = 0;
            for(ConstellationEntity tmp : constellationEntities) {
                // 접속자가 소유하고 있는 별자리와 조회하려는 별자리가 일치하는지 확인
                if(tmp.getId() == constellationId) {
                    break;
                } else {
                    if(++idx == constellationEntities.getTotalElements()) {
                        throw new ByeolDamException(ErrorCode.INVALID_PERMISSION, String.format("%s has no permission", email));
                    }
                }
            }
        }

        return userEntityPage.map(User::fromEntity);
    }

    // 관리자와 유저 UserRole 맞바꾸기
    public void roleModify(Long constellationId, Long userId, String email) {
        // user 존재하는지 확인
        getUserEntityByIdOrException(userId);

        // 사용자가 admin이라면 별자리 Entity 반환
        ConstellationEntity constellationEntity = getConstellationEntityIfAdminOrException(constellationId, email);
        System.out.println("constellation : " + constellationId);

        // 대상 user, ADMIN으로 권한 변경
        changeRole(userId, constellationEntity, ADMIN);

        // ADMIN, USER로 권한 변경
        Long adminId = getUserEntityByEmailOrException(email).getId();
        try {
            // 도중 오류 발생 시 user 권한 되돌리기
            changeRole(adminId, constellationEntity, USER);
        } catch(ByeolDamException e) {
            changeRole(userId, constellationEntity,USER);
        }
    }


    //TODO : 별자리 커스텀


    // 별자리가 존재하는지 확인
    private ConstellationEntity getConstellationEntityOrException(Long constellationId) {
        return constellationRepository.findById(constellationId)
                .orElseThrow(() ->
                        new ByeolDamException(ErrorCode.CONSTELLATION_NOT_FOUND, String.format("%s not founded", Long.toString(constellationId))));
    }

    // 유저가 존재하는지 확인
    private UserEntity getUserEntityByEmailOrException(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new ByeolDamException(ErrorCode.USER_NOT_FOUND, String.format("%s not founded", email)));
    }

    private UserEntity getUserEntityByIdOrException(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() ->
                        new ByeolDamException(ErrorCode.USER_NOT_FOUND, String.format("user not founded")));
    }

    // 사용자가 admin인지 확인
    private ConstellationEntity getConstellationEntityIfAdminOrException(Long constellationId, String email){
        UserEntity userEntity = getUserEntityByEmailOrException(email);                                                            // 현재 사용자 user entity
        ConstellationEntity constellationEntity = getConstellationEntityOrException(constellationId);
        System.out.println("constellationId : " + constellationId);
        UserEntity adminEntity = constellationEntity.getAdminEntity();
        System.out.println("adminId : " + adminEntity.getId());

        if(adminEntity != null) {
            System.out.println(userEntity.getId());
            // admin이어야 삭제 가능
            if(adminEntity.getId() != userEntity.getId()) {
                throw new ByeolDamException(ErrorCode.INVALID_PERMISSION,
                        String.format("%s has no permission with %s", email, Long.toString(constellationId)));
            }


            return constellationEntity;
        } else {
            throw new ByeolDamException(ErrorCode.INVALID_REQUEST, String.format("%s has no admin", Long.toString(constellationId)));
        }
    }

    private void changeRole(Long userId, ConstellationEntity constellationEntity, ConstellationUserRole role) {
        System.out.println("userId : " + userId);
        // 관리자 변경이 일어날 별자리

        UserEntity userEntity = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ByeolDamException(ErrorCode.USER_NOT_FOUND, String.format("%s not founded", Long.toString(userId))));

        // role 데이터를 저장하고 있는 별자리회원 Entity
        System.out.println("userId : " + userId);
        ConstellationUserEntity constellationUserEntity = constellationUserRepository.findByUserEntityAndConstellationEntity(userEntity, constellationEntity)
                .orElseThrow(() ->
                        new ByeolDamException(ErrorCode.CONSTELLATION_USER_NOT_FOUND, String.format("%s, %s has no constellationUserEntity", Long.toString(userId), Long.toString(constellationEntity.getId()))));
        constellationUserEntity.setConstellationUserRole(role);
        constellationUserRepository.saveAndFlush(constellationUserEntity);
    }
}
