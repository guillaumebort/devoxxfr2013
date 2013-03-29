$(function() {

    // Search
    var search = function(query) {
        var stream = new EventSource(Router.controllers.Application.search(encodeURIComponent(query)).url)

        $(stream).on('message', function(e) {
            var tweet = JSON.parse(e.originalEvent.data)
            $('<img>').attr('src', tweet.image).load(function() {
                $('#images').removeClass('loading').append($('<li>').append(this))
            })
        })
    }

    $('#query')
        .keypress(function(e) {
            if(e.keyCode == 13) {
                $(this).blur()
                $('#images').addClass('loading')
                search($(this).val())
            }
        })


})