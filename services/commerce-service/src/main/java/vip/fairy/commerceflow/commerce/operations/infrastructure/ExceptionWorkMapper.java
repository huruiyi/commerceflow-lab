package vip.fairy.commerceflow.commerce.operations.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.operations.domain.ExceptionWork;
import vip.fairy.commerceflow.commerce.operations.domain.ExceptionWork.Filter;

@Mapper
public interface ExceptionWorkMapper {

  long count(@Param("merchant") Long merchant, @Param("filter") Filter filter);

  List<ExceptionWork> page(@Param("merchant") Long merchant, @Param("filter") Filter filter,
                           @Param("offset") long offset, @Param("size") int size);
}
