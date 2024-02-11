package com.ssafy.star.common.infra.S3;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

import static com.ssafy.star.common.utils.ImageUtils.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class S3uploader {

    private final AmazonS3 amazonS3;
    private static final int TARGET_HEIGHT = 1024;
    private static final int PROFILE_HEIGHT = 128;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    public String upload(MultipartFile multipartFile, String dirName, int height) throws IOException {

        BufferedImage resizedImage = resizeImage(multipartFile, TARGET_HEIGHT);
        File uploadFile = convert(resizedImage, multipartFile.getOriginalFilename());

        String fileName = getFileName(uploadFile, dirName);
        String uploadImageUrl = putS3(uploadFile, fileName);

        removeNewFile(uploadFile);

        return uploadImageUrl;
    }


    public String uploadThumbnail(MultipartFile multipartFile, String dirName) throws IOException {

        BufferedImage resizedThumbnail = resizeThumbnail(multipartFile);
        File uploadThumbnail = convert(resizedThumbnail, multipartFile.getOriginalFilename());

        String fileName = getFileName(uploadThumbnail, dirName);
        String uploadThumbnailUrl = putS3(uploadThumbnail, fileName);

        removeNewFile(uploadThumbnail);

        return uploadThumbnailUrl;
    }

    public String uploadProfile(MultipartFile multipartFile, String dirName) throws IOException {

        BufferedImage resizedProfile = resizeImage(multipartFile, PROFILE_HEIGHT);
        File uploadProfile = convert(resizedProfile, multipartFile.getOriginalFilename());

        String fileName = getFileName(uploadProfile, dirName);
        String uploadProfileUrl = putS3(uploadProfile, fileName);

        removeNewFile(uploadProfile);

        return uploadProfileUrl;
    }

    public String getFileName(File file, String dirName){
        String uuid = UUID.randomUUID().toString();
        String extension = file.getName().substring(file.getName().lastIndexOf(".") + 1);
        if (extension.equals("blob")) extension = "png";

        return dirName + "/" + uuid + "." + extension;
    }

    private void removeNewFile(File targetFile) {
        if(targetFile.delete()){
            log.info("파일이 삭제되었습니다.");
        }else {
            log.info("파일이 삭제되지 않았습니다.");
        }
    }

    private String putS3(File uploadFile, String fileName) {
        amazonS3.putObject(
                new PutObjectRequest(bucket, fileName, uploadFile)
                        .withCannedAcl(CannedAccessControlList.PublicRead)
        );
        return amazonS3.getUrl(bucket, fileName).toString();
    }

    //TODO: 이미지 삭제가 실패한 경우 고려
    public void deleteImageFromS3(String filePath){
        String splitStr = ".com/";
        String fileName = filePath.substring(filePath.lastIndexOf(splitStr)+splitStr.length());
        try{
            amazonS3.deleteObject(new DeleteObjectRequest(bucket, fileName));
        }catch (Exception e){
            throw new IllegalStateException("이미지 삭제 중 문제가 발생했습니다.");
        }
    }

}
