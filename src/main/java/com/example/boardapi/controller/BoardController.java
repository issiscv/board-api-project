package com.example.boardapi.controller;

import com.example.boardapi.domain.Board;
import com.example.boardapi.domain.Comment;
import com.example.boardapi.domain.Member;
import com.example.boardapi.domain.enumtype.BoardType;
import com.example.boardapi.dto.board.request.BoardCreateRequestDto;
import com.example.boardapi.dto.board.request.BoardEditRequestDto;
import com.example.boardapi.dto.board.response.*;
import com.example.boardapi.dto.comment.request.CommentCreateRequestDto;
import com.example.boardapi.dto.comment.request.CommentEditRequestDto;
import com.example.boardapi.dto.comment.response.CommentCreateResponseDto;
import com.example.boardapi.dto.comment.response.CommentEditResponseDto;
import com.example.boardapi.dto.comment.response.CommentRetrieveResponseDto;
import com.example.boardapi.exception.exception.NotValidQueryStringException;
import com.example.boardapi.security.JWT.JwtTokenProvider;
import com.example.boardapi.service.BoardService;
import com.example.boardapi.service.CommentService;
import io.swagger.annotations.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@RequiredArgsConstructor
@RequestMapping("/boards")
@Slf4j
public class BoardController {

    private final BoardService boardService;

    private final JwtTokenProvider jwtTokenProvider;

    private final ModelMapper modelMapper;

    private final CommentService commentService;

    //작성 POST
    @ApiOperation(value = "게시글 작성", notes = "BoardCreateRequestDto DTO 를 통해 게시글을 생성합니다.")
    @ApiResponses({
            @ApiResponse(code = 201, message = "게시글 생성 성공"),
            @ApiResponse(code = 400, message = "잘못된 요청 or 검증 실패"),
            @ApiResponse(code = 401, message = "토큰 검증 실패(인증 실패)")
    })
    @PostMapping("")
    public ResponseEntity<EntityModel<BoardCreateResponseDto>> createBoard(@ApiParam(value = "게시글 생성 DTO", required = true) @RequestBody @Valid BoardCreateRequestDto boardCreateRequestDto,
                                      @ApiParam(value = "게시글 종류 쿼리 스트링", required = true, example = "tech, qna, free") @RequestParam(required = true) String type, HttpServletRequest request) {
        //request 헤더 값을 가져와, 회원 조회 : 누가 작성했는지 알기 위해서
        String token = jwtTokenProvider.resolveToken(request);
        Member member = jwtTokenProvider.getMember(token);

        //DTO 를 Board 엔티티로 매핑 하고 저장
        Board mappedBoard = modelMapper.map(boardCreateRequestDto, Board.class);
        mappedBoard.setMember(member);

        //쿼리스트링에 맞게 엔티티에 매핑
        if (type.equals("free")) {
            mappedBoard.setBoardType(BoardType.FREE);
        } else if (type.equals("qna")) {
            mappedBoard.setBoardType(BoardType.QNA);
        } else if (type.equals("tech")) {
            mappedBoard.setBoardType(BoardType.TECH);
        } else {
            throw new NotValidQueryStringException("free, qna, tech 의 쿼리스트링만 입력 가능합니다.");
        }

        Board savedBoard = boardService.save(mappedBoard);

        //응답 DTO
        BoardCreateResponseDto boardCreateResponseDto = modelMapper.map(savedBoard, BoardCreateResponseDto.class);
        boardCreateResponseDto.setAuthor(member.getName());

        //데이터베이스에 생성하였기에 주소를 설정해준다 해준다.
        URI uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(savedBoard.getId()).toUri();
        //profile 주소를 hateoas에 추가를 위해 ip 주소를 가져온다.
        String ip = getIp();

        //hateoas 기능 추가
        EntityModel<BoardCreateResponseDto> model = EntityModel.of(boardCreateResponseDto);
        WebMvcLinkBuilder self =
                linkTo(methodOn(this.getClass()).createBoard(new BoardCreateRequestDto(), "qna", request));
        WebMvcLinkBuilder retrieve = linkTo(methodOn(this.getClass()).retrieveBoard(savedBoard.getId()));

        model.add(self.withSelfRel());
        model.add(retrieve.withRel("게시글 조회"));
        model.add(Link.of("http://"+ip+":8080/swagger-ui/#/", "profile"));

        return ResponseEntity.created(uri).body(model);
    }

    //단건 조회 및 자세한 조회(댓글 까지) GET
    @ApiOperation(value = "게시글 단건 조회", notes = "게시글 엔티티의 PK를 경로 변수에 넣어 조회합니다.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "게시글 조회 성공"),
            @ApiResponse(code = 400, message = "존재하지 않는 게시글입니다."),
    })
    @GetMapping("/{boardId}")
    public ResponseEntity<EntityModel<BoardRetrieveResponseDto>> retrieveBoard(@ApiParam(value = "게시글 PK", required = true) @PathVariable Long boardId) {
        
        //해당 PK 에 해당하는 게시판 엔티티 조회 및 게시글 조회 검증
        Board board = boardService.retrieveOne(boardId);
        //게시글에 해당하는 댓글 리스트
        List<Comment> comments = commentService.retrieveOneByBoardId(boardId);
        List<CommentRetrieveResponseDto> commentResponseDtoList = new ArrayList<>();
        
        //조회한 댓글 엔티티를 DTO 로 변환
        for (Comment comment : comments) {

            CommentRetrieveResponseDto commentRetrieveResponseDto = CommentRetrieveResponseDto.builder()
                    .id(comment.getId())
                    .memberId(comment.getBoard().getMember().getId())
                    .boardId(board.getId())
                    .author(comment.getMember().getName())
                    .content(comment.getContent())
                    .createdDate(comment.getCreatedDate())
                    .lastModifiedDate(comment.getLastModifiedDate())
                    .likes(comment.getLikes())
                    .build();

            commentResponseDtoList.add(commentRetrieveResponseDto);
        }

        //게시판 조회 시 해당 DTO 로 변환
        BoardRetrieveResponseDto boardRetrieveResponseDto = modelMapper.map(board, BoardRetrieveResponseDto.class);
        //응답 시 필드 명이 author 이므로 따로 세팅한다.
        boardRetrieveResponseDto.setMemberId(board.getMember().getId());
        boardRetrieveResponseDto.setAuthor(board.getMember().getName());
        boardRetrieveResponseDto.setComments(commentResponseDtoList);

        //ip
        String ip = getIp();

        //hateoas 기능 추가
        EntityModel<BoardRetrieveResponseDto> model = EntityModel.of(boardRetrieveResponseDto);
        WebMvcLinkBuilder self = linkTo(methodOn(this.getClass()).retrieveBoard(boardId));
        //self
        model.add(self.withSelfRel());
        model.add(Link.of("http://"+ip+":8080/swagger-ui/#/", "profile"));


        return ResponseEntity.ok().body(model);
    }



    //전체 조회 GET
    @ApiOperation(value = "게시글 전체 조회", notes = "게시글 엔티티의 PK를 경로 변수에 넣어 조회합니다.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "게시글 전체 조회 성공"),
    })
    @GetMapping("")
    public ResponseEntity<EntityModel<BoardRetrieveAllPagingResponseDto>> retrieveAllBoard(
            @ApiParam(value = "페이징을 위한 쿼리 스트링", required = false) @RequestParam(required = false) Integer page,
            @ApiParam(value = "게시글 종류 쿼리 스트링", required = true, example = "tech, qna, free") @RequestParam String type) {
        //몇 번 페이지를 찾을지 쿼리를 날리기 위한 변수
        int num = 0;

        if (page != null) {
            num = page - 1;
        } else {
            //쿼리스트링이 없을 경우 1로 초기화
            page = 1;
        }
        
        //페이징 기준
        PageRequest pageRequest = PageRequest.of(num, 15, Sort.by(Sort.Direction.DESC, "createdDate"));
        //페이징 방식 대로 조회
        Page<Board> boardPage = boardService.retrieveAllWithPagingByType(pageRequest, type);

        long totalElements = boardPage.getTotalElements();

        //총 페이지 수
        int totalPages = boardPage.getTotalPages();
        //해당 페이지의 컨텐트들
        List<Board> content = boardPage.getContent();

        List<BoardRetrieveOneResponseDto> boardRetrieveOneResponseDtoList = content.stream().map(board -> {
                    BoardRetrieveOneResponseDto boardRetrieveOneResponseDto = modelMapper.map(board, BoardRetrieveOneResponseDto.class);
                    boardRetrieveOneResponseDto.setAuthor(board.getCreatedBy());
                    return boardRetrieveOneResponseDto;
                }
        ).collect(Collectors.toList());

        BoardRetrieveAllPagingResponseDto boardRetrieveAllPagingResponseDto =
                new BoardRetrieveAllPagingResponseDto(num+1, totalPages, (int)totalElements, boardRetrieveOneResponseDtoList);

        //ip
        String ip = getIp();

        //hateoas 기능 추가
        EntityModel<BoardRetrieveAllPagingResponseDto> model = EntityModel.of(boardRetrieveAllPagingResponseDto);
        WebMvcLinkBuilder self = linkTo(methodOn(this.getClass()).retrieveAllBoard(page, type));
        //self
        model.add(self.withSelfRel());
        model.add(Link.of("http://"+ip+":8080/swagger-ui/#/", "profile"));

        //페이징 hateoas 를 위한 로직이다.
        if (page > 1) {
            WebMvcLinkBuilder prev = linkTo(methodOn(this.getClass()).retrieveAllBoard(page - 1, type));
            model.add(prev.withRel("이전"));
        }
        if (page < totalPages) {
            WebMvcLinkBuilder next = linkTo(methodOn(this.getClass()).retrieveAllBoard(page + 1, type));
            model.add(next.withRel("다음"));
        }

        return ResponseEntity.ok().body(model);
    }
    
    //수정 PUT
    @ApiOperation(value = "게시글 수정", notes = "게시글을 수정합니다. BoardEditRequestDto DTO 를 사용합니다.")
    @ApiResponses({
            @ApiResponse(code = 201, message = "게시글이 수정되었습니다."),
            @ApiResponse(code = 400, message = "존재하지 않는 게시글 입니다. or 잘못된 요청 or 검증 실패"),
            @ApiResponse(code = 401, message = "토큰 검증 실패(인증 실패)"),
    })
    @PutMapping("/{boardId}")
    public ResponseEntity<EntityModel<BoardEditResponseDto>> editBoard(@ApiParam(value = "게시글 수정 DTO", required = true) @RequestBody @Valid
                                                BoardEditRequestDto boardEditRequestDto,
                                    @ApiParam(value = "게시판 PK", required = true) @PathVariable Long boardId) {

        Board board = boardService.editBoard(boardId, boardEditRequestDto);

        BoardEditResponseDto boardEditResponseDto = modelMapper.map(board, BoardEditResponseDto.class);
        boardEditResponseDto.setAuthor(board.getMember().getName());

        URI uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(board.getId()).toUri();


        //ip
        String ip = getIp();

        //hateoas 기능 추가
        EntityModel<BoardEditResponseDto> model = EntityModel.of(boardEditResponseDto);
        WebMvcLinkBuilder self = linkTo(methodOn(this.getClass()).editBoard(boardEditRequestDto, boardId));
        //self
        model.add(self.withSelfRel());
        model.add(Link.of("http://"+ip+":8080/swagger-ui/#/", "profile"));

        return ResponseEntity.created(uri).body(model);
    }


    //삭제 DELETE
    @ApiOperation(value = "게시글 삭제", notes = "게시글 엔티티의 PK를 경로 변수에 넣어 삭제합니다.")
    @ApiResponses({
            @ApiResponse(code = 204, message = "게시글이 삭제되었습니다.."),
            @ApiResponse(code = 400, message = "존재하지 않는 게시글 입니다."),
            @ApiResponse(code = 401, message = "토큰 검증 실패(인증 실패)")
    })
    @DeleteMapping("/{boardId}")
    public ResponseEntity deleteBoard(@ApiParam(value = "게시판 PK", required = true) @PathVariable Long boardId) {
        boardService.deleteBoard(boardId);

        return ResponseEntity.noContent().build();
    }

    @ApiOperation(value = "게시글의  좋아요", notes = "게시글을 좋아합니다.")
    @ApiResponses({
            @ApiResponse(code = 204, message = "게시글 좋아요를 정상적으로 수행했습니다."),
            @ApiResponse(code = 400, message = "존재하지 않는 게시글 입니다."),
            @ApiResponse(code = 401, message = "토큰 검증 실패(인증 실패)"),
    })
    @PutMapping("/{boardId}/likes")
    public ResponseEntity updateLike(@ApiParam(value = "게시판 PK", required = true) @PathVariable Long boardId) {
        boardService.updateBoardLike(boardId);

        return ResponseEntity.noContent().build();
    }

    /**
     * 이 밑으로는 댓글 API
     */

    //특정 게시판에 댓글을 쓰는 API
    @ApiOperation(value = "게시글의 댓글 작성", notes = "게시글의 댓글을 추가합니다. CommentCreateRequestDto DTO 를 사용합니다.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "댓글 작성을 완료했습니다."),
            @ApiResponse(code = 400, message = "존재하지 않는 게시글 입니다. or 잘못된 요청 or 검증 실패"),
            @ApiResponse(code = 401, message = "토큰 검증 실패(인증 실패)")
    })
    @PostMapping("/{boardId}/comments")
    public ResponseEntity<EntityModel<CommentCreateResponseDto>> createComment(@RequestBody @Valid @ApiParam(value = "댓글 DTO", required = true) CommentCreateRequestDto commentCreateRequestDto,
                                        @ApiParam(value = "게시판 PK", required = true) @PathVariable Long boardId, HttpServletRequest request) {

        //글쓴이의 정보(토큰의 정보)
        String token = jwtTokenProvider.resolveToken(request);
        Member member = jwtTokenProvider.getMember(token);

        //게시글 엔티티 조회
        Board board = boardService.retrieveOne(boardId);

        //DTO 를 변환 엔티티로 변환
        Comment comment = modelMapper.map(commentCreateRequestDto, Comment.class);
        comment.setBoard(board);
        comment.setMember(member);
        Comment saveComment = commentService.save(comment);

        //엔티티를 DTO로 변환
        CommentCreateResponseDto commentResponseDto = modelMapper.map(saveComment, CommentCreateResponseDto.class);
        commentResponseDto.setAuthor(member.getName());//작성자
        commentResponseDto.setId(saveComment.getId());//댓글 기본키
        commentResponseDto.setBoardId(board.getId());//게시글 기본키

        //URI
        URI uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saveComment.getId())
                .toUri();
        //ip
        String ip = getIp();

        //hateoas 기능 추가
        EntityModel<CommentCreateResponseDto> model = EntityModel.of(commentResponseDto);
        WebMvcLinkBuilder self = linkTo(methodOn(this.getClass()).createComment(commentCreateRequestDto, boardId, request));
        WebMvcLinkBuilder boardLink = linkTo(methodOn(this.getClass()).retrieveBoard(boardId));

        //self
        model.add(self.withSelfRel());
        //profile
        model.add(Link.of("http://"+ip+":8080/swagger-ui/#/", "profile"));
        //게시글
        model.add(boardLink.withRel("게시글"));
        return ResponseEntity.created(uri).body(model);
    }

    //댓글 수정
    @ApiOperation(value = "게시글의 댓글 수정", notes = "게시글의 댓글을 수정합니다. CommentEditRequestDto DTO 를 사용합니다.")
    @ApiResponses({
            @ApiResponse(code = 201, message = "댓글 수정을 완료했습니다."),
            @ApiResponse(code = 400, message = "존재하지 않는 게시글 입니다. or 잘못된 요청 or 검증 실패"),
            @ApiResponse(code = 401, message = "토큰 검증 실패(인증 실패)")
    })
    @PutMapping("/{boardId}/comments/{commentId}")
    public ResponseEntity<EntityModel<CommentEditResponseDto>> editComment(@ApiParam(value = "댓글 수정 DTO", required = true) @RequestBody @Valid CommentEditRequestDto commentEditRequestDto,
                                      @ApiParam(value = "게시판 PK", required = true) @PathVariable Long boardId,
                                      @ApiParam(value = "댓글 PK", required = true) @PathVariable Long commentId) {
        //게시글이 존재하는지 검사
        boardService.retrieveOne(boardId);

        //댓글 수정
        Comment comment = commentService.editComment(commentId, commentEditRequestDto);

        CommentEditResponseDto commentEditResponseDto = modelMapper.map(comment, CommentEditResponseDto.class);
        commentEditResponseDto.setAuthor(comment.getMember().getName());
        commentEditResponseDto.setBoardId(boardId);

        URI uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .build()
                .toUri();

        String ip = getIp();

        //hateoas 기능 추가
        EntityModel<CommentEditResponseDto> model = EntityModel.of(commentEditResponseDto);
        WebMvcLinkBuilder self = linkTo(methodOn(this.getClass()).editComment(commentEditRequestDto, boardId, commentId));

        //self
        model.add(self.withSelfRel());
        //profile
        model.add(Link.of("http://"+ip+":8080/swagger-ui/#/", "profile"));

        return ResponseEntity.created(uri).body(model);
    }

    //댓글 삭제
    @ApiOperation(value = "게시글의 댓글 삭제", notes = "게시글의 댓글을 삭제합니다.")
    @ApiResponses({
            @ApiResponse(code = 204, message = "댓글 삭제를 완료했습니다."),
            @ApiResponse(code = 400, message = "존재하지 않는 게시글 입니다."),
            @ApiResponse(code = 401, message = "토큰 검증 실패(인증 실패)"),
    })
    @DeleteMapping("/{boardId}/comments/{commentId}")
    public ResponseEntity deleteComment(@ApiParam(value = "게시글 PK", required = true) @PathVariable Long boardId,
                                        @ApiParam(value = "댓글 PK", required = true) @PathVariable Long commentId) {
        //게시글이 존재하는지 검사
        boardService.retrieveOne(boardId);
        //삭제
        commentService.deleteComment(commentId);

        return ResponseEntity.noContent().build();
    }

    @ApiOperation(value = "게시글의 댓글 좋아요", notes = "게시글의 댓글을 좋아합니다..")
    @ApiResponses({
            @ApiResponse(code = 204, message = "댓글 좋아요를 정상적으로 수행했습니다."),
            @ApiResponse(code = 400, message = "존재하지 않는 게시글 입니다."),
            @ApiResponse(code = 401, message = "토큰 검증 실패(인증 실패)"),
    })
    @PutMapping("/{boardId}/comments/{commentId}/likes")
    public ResponseEntity updateCommentLike(@ApiParam(value = "게시글 PK", required = true) @PathVariable Long boardId,
                                            @ApiParam(value = "댓글 PK", required = true) @PathVariable Long commentId) {
        //게시글이 존재하는지 검사
        boardService.retrieveOne(boardId);

        commentService.updateCommentLike(commentId);

        return ResponseEntity.noContent().build();
    }

    private String getIp() {
        String ip = "";
        try {
            InetAddress local = InetAddress.getLocalHost();
            ip = local.getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return ip;
    }
}
