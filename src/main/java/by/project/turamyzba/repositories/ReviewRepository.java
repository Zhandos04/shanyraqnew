package by.project.turamyzba.repositories;

import by.project.turamyzba.entities.Review;
import by.project.turamyzba.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Integer> {
    List<Review> findByCreatedBy(User user);
    List<Review> findByAddressedTo_Id(Integer addressedToId);}